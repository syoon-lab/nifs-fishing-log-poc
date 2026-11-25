import unittest
from transcript_selection import extraction_text, select_transcript


class TranscriptSelection(unittest.TestCase):
    def test_empty_dialect_keeps_original_and_selects_base(self):
        result = {'asr_text': '기본 전사', 'dialect_text': '-'}
        self.assertEqual(select_transcript(result, True), '기본 전사')
        self.assertEqual(result['dialect_text'], '-')
        self.assertEqual(result['text_source'], 'base_fallback')
        self.assertEqual(result['transcription_status']['dialect'], 'EMPTY')

    def test_valid_but_inaccurate_dialect_is_not_replaced(self):
        result = {'asr_text': '기본 전사', 'dialect_text': '삼채오 킬로그램'}
        self.assertEqual(select_transcript(result, True), '삼채오 킬로그램')
        result.update(text_source='base_fallback', fallback_reason='dialect_empty')
        with self.assertRaises(ValueError):
            extraction_text(result)
        result['text_source'] = 'base'
        with self.assertRaises(ValueError):
            extraction_text(result)

    def test_error_retained_and_no_content_is_not_usable(self):
        result = {'asr_text': '기본 전사', 'dialect_text': None, 'dialect_error': 'load failed'}
        self.assertEqual(select_transcript(result, True), '기본 전사')
        self.assertEqual(result['fallback_reason'], 'dialect_error')
        self.assertEqual(result['transcription_status']['dialect'], 'FAILED')
        empty = {'asr_text': '-', 'dialect_text': '-'}
        self.assertEqual(select_transcript(empty, True), '-')
        self.assertEqual(empty['text_source'], 'base')
        self.assertIsNone(empty['fallback_reason'])

    def test_base_mode_and_legacy_outputs_keep_their_meaning(self):
        result = {'asr_text': '기본', 'dialect_text': None}
        self.assertEqual(select_transcript(result, False), '기본')
        self.assertEqual(result['text_source'], 'base')
        self.assertEqual(result['transcription_status']['dialect'], 'NOT_REQUESTED')
        self.assertEqual(extraction_text({'asr_text': '기본', 'dialect_text': '-'}), '-')


if __name__ == '__main__':
    unittest.main()
