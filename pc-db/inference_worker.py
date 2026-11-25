#!/usr/bin/env python3
"""Persistent, offline inference worker; JSONL requests and responses on stdio.

Run with the model Python configured in models.json. This module imports no ML
packages until a request arrives, and never writes to models or source audio.
Model diagnostics go to stderr so stdout remains a JSONL protocol.
"""
from __future__ import annotations

import argparse
import contextlib
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import traceback

from transcript_selection import extraction_text, meaningful, select_transcript

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")


def strict_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    def reject(value):
        raise ValueError(f"Non-finite JSON number: {value}")
    value = json.loads(text, object_pairs_hook=pairs, parse_constant=reject)
    # This also rejects overflowed literals such as 1e999.
    json.dumps(value, allow_nan=False)
    return value


def parse_extraction(raw):
    """Decode JSON without silently repairing, inventing, or changing values."""
    text = raw.strip()
    if text.startswith("```") and text.endswith("```"):
        lines = text.splitlines()
        if lines[0].strip() not in ("```", "```json", "```JSON"):
            raise ValueError("Unsupported extraction code fence")
        text = "\n".join(lines[1:-1]).strip()
    value = strict_json(text)
    if not isinstance(value, dict):
        raise ValueError("Extraction must be a JSON object")
    return value


def reviewed_result(review, parent):
    """Build extraction input from a frozen parent; never run or relabel ASR."""
    from transcript_review import validate_review, reviewed_text
    review = validate_review(review)
    if not isinstance(parent, dict) or parent.get('run_id') != review['parent_run_id']:
        raise ValueError('검토 전사의 이전 처리 연결이 다릅니다.')
    for key in ('asr_text', 'dialect_text', 'dialect_error'):
        if parent.get(key) is not None and not isinstance(parent[key], str):
            raise ValueError('이전 전사 정보가 올바르지 않습니다: ' + key)
    for key in ('base_segments', 'dialect_segments'):
        if not isinstance(parent.get(key), list):
            raise ValueError('이전 전사 구간 정보가 올바르지 않습니다: ' + key)
    result = {key: copy.deepcopy(parent.get(key)) for key in (
        'asr_text', 'dialect_text', 'dialect_error', 'base_segments', 'dialect_segments',
        'transcription_status', 'duration_seconds', 'sample_rate', 'audio_samples')}
    result.update(processing_mode='transcript_review', text_source='reviewed',
                  transcript_review=copy.deepcopy(review), reviewed_text=review['text'],
                  asr_reused_from_run_id=parent['run_id'],
                  asr_parent_model_sha256=parent.get('model_sha256'), fallback_reason=None)
    result['segments'] = copy.deepcopy(parent['base_segments'] if review['selection'] == 'base' else
        parent['dialect_segments'] if review['selection'] == 'dialect' else [])
    reviewed_text(result)
    return result


class Worker:
    def __init__(self, config_path):
        self.config_path = Path(config_path).resolve()
        self.config = json.loads(self.config_path.read_text(encoding="utf-8"))
        self.asr = None
        self.extractor = None
        self.tokenizer = None
        self.versions = {}
        self.dialect_loaded = False
        self.prompt = (self.local_path(self.config.get("extraction_prompt", "extraction_prompt.txt"))
                       .read_text(encoding="utf-8")) if self.config.get("extractor") else None

    def local_path(self, value):
        path = Path(value).expanduser()
        if not path.is_absolute():
            path = self.config_path.parent / path
        return path.resolve(strict=True)

    def load_asr(self):
        if self.asr is not None:
            return
        import torch
        import transformers
        from transformers import WhisperForConditionalGeneration, WhisperProcessor, pipeline

        torch.set_num_threads(int(self.config.get("threads", 8)))
        torch.manual_seed(0)
        self.versions.update(torch=torch.__version__, transformers=transformers.__version__)
        spec = self.config["asr"]["base"]
        if spec.get("engine") not in ("transformers-whisper", "transformers"):
            raise ValueError("Unsupported base ASR engine")
        path = self.local_path(spec["path"])
        device = spec.get("device", "cuda:1")
        dtype = torch.float16 if device.startswith("cuda") else torch.float32
        processor = WhisperProcessor.from_pretrained(path, local_files_only=True)
        model = WhisperForConditionalGeneration.from_pretrained(
            path, local_files_only=True, dtype=dtype, attn_implementation="sdpa")
        model = model.to(device).eval()
        # Explicit arguments avoid inheriting unrelated training-time generation settings.
        model.generation_config.forced_decoder_ids = None
        self.asr_model = model
        self.asr = pipeline(
            "automatic-speech-recognition", model=model,
            tokenizer=processor.tokenizer, feature_extractor=processor.feature_extractor,
            device=device, dtype=dtype)

    def load_dialect(self):
        if self.dialect_loaded:
            return
        dialect = self.config["asr"].get("dialect")
        if not dialect:
            return
        if dialect.get("engine") != "peft-whisper":
            raise ValueError("Unsupported dialect ASR engine; expected peft-whisper")
        import peft
        self.versions["peft"] = peft.__version__
        adapter = self.local_path(dialect["path"])
        device = self.config["asr"]["base"].get("device", "cuda:1")
        # The base pass has already completed. Keep its result if loading fails.
        model = peft.PeftModel.from_pretrained(
            self.asr_model.to("cpu"), adapter, local_files_only=True, torch_device="cpu")
        self.asr_model = model.to(device).eval()
        self.asr.model = self.asr_model
        self.dialect_loaded = True

    def read_audio(self, path):
        import numpy as np
        ffmpeg = self.config.get("ffmpeg", "ffmpeg")
        ffprobe = self.config.get("ffprobe", "ffprobe")
        probe = subprocess.run(
            [ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries",
             "stream=codec_name,sample_rate,channels:format=duration", "-of", "json", str(path)],
            capture_output=True, check=True, text=True, timeout=60)
        metadata = json.loads(probe.stdout)
        if not metadata.get("streams"):
            raise ValueError("Input has no audio stream")
        # A complete decode is required. No -t or slicing is used on the waveform.
        decoded = subprocess.run(
            [ffmpeg, "-nostdin", "-v", "error", "-i", str(path), "-map", "0:a:0",
             "-ac", "1", "-ar", "16000", "-f", "f32le", "pipe:1"],
            capture_output=True, check=True, timeout=300)
        waveform = np.frombuffer(decoded.stdout, dtype="<f4").copy()
        if not len(waveform) or not np.isfinite(waveform).all():
            raise ValueError("Audio decoded to empty or non-finite samples")
        return waveform, metadata

    def transcribe(self, waveform, base=False):
        self.load_asr()
        import torch
        if not base:
            self.load_dialect()
        adapter_context = self.asr_model.disable_adapter() if self.dialect_loaded and base else contextlib.nullcontext()
        with adapter_context, torch.inference_mode():
            result = self.asr(
                {"array": waveform, "sampling_rate": 16000},
                chunk_length_s=25, stride_length_s=3, batch_size=1,
                return_timestamps=True,
                generate_kwargs={"language": "ko", "task": "transcribe", "num_beams": 1,
                                 "do_sample": False, "max_new_tokens": 440})
        duration = len(waveform) / 16000
        segments = []
        for item in result.get("chunks", []):
            begin, end = item.get("timestamp", (None, None))
            segments.append({"start": begin, "end": end, "text": item.get("text", "")})
        return {"text": result.get("text", "").strip(), "segments": segments,
                "duration_seconds": duration}

    def load_extractor(self):
        if self.extractor is not None:
            return
        import torch
        import transformers
        from transformers import AutoTokenizer, AutoModelForCausalLM
        self.versions.update(torch=torch.__version__, transformers=transformers.__version__)
        torch.set_num_threads(int(self.config.get("threads", 8)))
        spec = self.config["extractor"]
        if spec.get("engine") not in ("transformers", "transformers-causal"):
            raise ValueError("Unsupported extractor engine")
        path = self.local_path(spec["path"])
        self.tokenizer = AutoTokenizer.from_pretrained(path, local_files_only=True)
        device = spec.get("device", "cuda:0")
        dtype = torch.bfloat16 if device.startswith("cuda") else torch.float32
        self.extractor = AutoModelForCausalLM.from_pretrained(
            path, local_files_only=True, dtype=dtype, attn_implementation="sdpa").to(device).eval()

    def extract(self, text, phase, source, previous_raw=None, validation_error=None, question_context=None):
        prompt = self.prompt
        data = {"recording_phase": phase, "transcript_source": source, "transcript": text}
        if question_context is not None:
            from answer_context import validate_question_context
            context = validate_question_context(question_context)
            data['linked_question'] = {key: context[key] for key in ('target_field', 'expected_unit', 'question')}
        content = json.dumps(data, ensure_ascii=False)
        messages = [{"role": "system", "content": prompt}, {"role": "user", "content": content}]
        if previous_raw is not None:
            messages.extend([
                {"role": "assistant", "content": previous_raw},
                {"role": "user", "content": "The previous output failed validation: " + str(validation_error)
                 + "\nReturn the full corrected JSON using only the original transcript above. Follow the exact schema. "
                 "Each claim needs field, value and an exact quote. Remove unsupported claims and explain them in uncertainties. Do not omit explicitly supported facts. Return only claims and uncertainties. "
                 "Do not treat validation messages as transcript facts."}])
        spec = self.config["extractor"]
        if spec.get("engine") == "ollama":
            from ollama_extract import extract, OllamaExtractionError
            try:
                raw, metadata = extract(spec, messages)
            except OllamaExtractionError as error:
                self.versions.update(error.metadata or {})
                return None, error.raw, str(error)
            self.versions.update(metadata)
            try:
                return parse_extraction(raw), raw, None
            except (ValueError, TypeError) as error:
                return None, raw, f"Invalid extraction JSON: {error}"
        self.load_extractor()
        import torch
        encoded = self.tokenizer.apply_chat_template(
            messages, tokenize=True, add_generation_prompt=True, return_tensors="pt", return_dict=True)
        encoded = {key: value.to(self.extractor.device) for key, value in encoded.items()}
        input_tokens = encoded["input_ids"].shape[-1]
        maximum = int(self.config["extractor"].get("max_new_tokens", 2048))
        context = int(self.extractor.config.max_position_embeddings)
        if input_tokens + maximum > context:
            raise ValueError("Full transcript exceeds extractor context; no truncation performed")
        with torch.inference_mode():
            tokens = self.extractor.generate(
                **encoded, do_sample=False, max_new_tokens=maximum, use_cache=True,
                pad_token_id=self.tokenizer.eos_token_id)
        generated = tokens[0, input_tokens:]
        raw = self.tokenizer.decode(generated, skip_special_tokens=True)
        if len(generated) >= maximum and int(generated[-1]) != self.tokenizer.eos_token_id:
            return None, raw, "Extraction token limit reached; raw partial output preserved"
        try:
            return parse_extraction(raw), raw, None
        except (ValueError, TypeError) as error:
            return None, raw, f"Invalid extraction JSON: {error}"

    def process(self, task):
        start = time.monotonic()
        if 'transcript_review' in task or 'transcript_parent' in task:
            result = reviewed_result(task.get('transcript_review'), task.get('transcript_parent'))
            context = task.get('question_context')
            if context is not None:
                from answer_context import validate_question_context
                context = validate_question_context(context, recording_id=task.get('recording_id'))
            self.extract_result(result, task.get('phase'), question_context=context)
            result['elapsed_seconds'] = round(time.monotonic() - start, 3)
            return result
        audio_path = Path(task["audio_path"]).expanduser().resolve(strict=True)
        waveform, metadata = self.read_audio(audio_path)
        base = self.transcribe(waveform, base=True)
        dialect = None
        dialect_error = None
        if self.config["asr"].get("dialect"):
            try:
                dialect = self.transcribe(waveform)
            except Exception as error:
                traceback.print_exc(file=sys.stderr)
                dialect_error = f"Dialect ASR failed: {type(error).__name__}: {error}"
                # A failed PEFT load may have partly modified the model; reload next request.
                self.asr = None
                self.asr_model = None
                self.dialect_loaded = False
        result = {
            "asr_text": base["text"], "dialect_text": dialect["text"] if dialect else None,
            "segments": [], "base_segments": base["segments"],
            "dialect_segments": dialect["segments"] if dialect else [],
            "duration_seconds": len(waveform) / 16000, "audio_samples": len(waveform),
            "sample_rate": 16000, "audio_metadata": metadata,
            "extraction": None, "extraction_raw": None, "warnings": [],
            "extraction_attempts": [],
            "engine_versions": self.versions,
        }
        if dialect_error:
            result["dialect_error"] = dialect_error
        context = task.get('question_context')
        if context is not None:
            from answer_context import validate_question_context
            context = validate_question_context(context, recording_id=task.get('recording_id'))
        self.extract_result(result, task.get("phase"), question_context=context)
        result['segments'] = result['dialect_segments'] if result.get('text_source') == 'dialect' else result['base_segments']
        result["elapsed_seconds"] = round(time.monotonic() - start, 3)
        return result

    def extract_result(self, result, phase=None, question_context=None):
        """Validate small evidence claims and assemble defaults in code, never in the LLM."""
        from extraction import assemble_claims
        from pipeline import validate_extraction
        result.setdefault("warnings", [])
        result.setdefault("extraction_attempts", [])
        result.setdefault('engine_versions', self.versions)
        if 'text_source' not in result:
            select_transcript(result, bool(self.config['asr'].get('dialect')))
        text = extraction_text(result)
        source = result['text_source']
        if source == 'base_fallback':
            warning = ('방언 전사 처리에 실패하여 기본 전사로 항목을 추출했습니다.'
                       if result.get('fallback_reason') == 'dialect_error' else
                       '방언 전사에 유효한 내용이 없어 기본 전사로 항목을 추출했습니다.')
            if warning not in result['warnings']:
                result['warnings'].append(warning)
        if question_context is not None:
            from answer_context import validate_question_context
            question_context = validate_question_context(question_context)
            result['question_context'] = question_context
        result["extraction"] = None
        if not meaningful(text):
            result["error"] = "유효한 전사 내용이 없습니다. 원음을 확인하세요."
            result["warnings"].append(result["error"])
            return result
        if not self.config.get("extractor"):
            result["error"] = "No extractor configured"
            return result
        raw = warning = None
        for attempt in range(2):
            result["extraction"] = None
            result.pop("grounding", None)
            try:
                payload, new_raw, new_warning = self.extract(
                    text, phase, source, previous_raw=raw if attempt else None,
                    validation_error=warning, question_context=question_context)
                raw, warning = new_raw, new_warning
                result["extraction_raw"] = raw
                result["claims"] = payload
                if not warning:
                    assembled = assemble_claims(payload, text, phase=phase, question_context=question_context)
                    result.update(assembled)
                    validate_extraction(result)
                    omitted = assembled.get("grounding", {}).get("coverage_warnings", [])
                    if omitted:
                        raise ValueError("명시한 내용 누락: " + "; ".join(omitted))
            except Exception as error:
                warning = f"{type(error).__name__}: {error}"
            result["extraction_attempts"].append({"attempt": attempt + 1, "raw": raw,
                                                  "validation_error": warning,
                                                  "extraction": result.get("extraction"),
                                                  "grounding": result.get("grounding")})
            if not warning:
                break
        if warning:
            result["error"] = warning
            result["warnings"].append(warning)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    worker = Worker(args.config)
    for line in sys.stdin:
        if not line.strip():
            continue
        task = {}
        try:
            task = strict_json(line)
            if not isinstance(task, dict):
                raise ValueError("Request must be a JSON object")
            with contextlib.redirect_stdout(sys.stderr):
                result = worker.process(task)
            response = {"id": task.get("id"), "result": result}
            if result.get("error"):
                response["error"] = result["error"]
        except Exception as error:
            traceback.print_exc(file=sys.stderr)
            response = {"id": task.get("id") if isinstance(task, dict) else None,
                        "error": f"{type(error).__name__}: {error}"}
        print(json.dumps(response, ensure_ascii=False, allow_nan=False), flush=True)


if __name__ == "__main__":
    main()
