"""Use only an explicit, same-work answer event to identify a numeric question.

Question metadata routes an answer; it is not evidence of an observed amount or
gear type. Prior phone fields, fingerprints, transcriptions and review values are
deliberately excluded. Unsupported or ambiguous links are left unconsumed.
"""
import json


QUESTIONS = {
    ('haul_quantity', 'haul_pot'): ('개', '이번에 걷은 통발은 몇 개입니까?'),
    ('haul_quantity', 'haul_net'): ('폭', '이번에 걷은 자망은 몇 폭입니까?'),
    ('redeploy_quantity', 'reset_pot'): ('개', '다시 놓은 통발은 몇 개입니까?'),
    ('redeploy_quantity', 'reset_net'): ('폭', '다시 놓은 자망은 몇 폭입니까?'),
}
CONTEXT_KEYS = {'version', 'source_id', 'work_id', 'recording_id', 'session_id',
                'operation_id', 'event_id', 'target_field', 'expected_unit', 'question'}


def validate_question_context(context, recording_id=None):
    """Validate the narrow worker contract; return a copy, or None for no link."""
    if context is None:
        return None
    if not isinstance(context, dict) or set(context) != CONTEXT_KEYS:
        raise ValueError('질문 연결 형식이 올바르지 않습니다.')
    if type(context['version']) is not int or context['version'] != 1:
        raise ValueError('지원하지 않는 질문 연결 버전입니다.')
    for key in CONTEXT_KEYS - {'version'}:
        value = context[key]
        if not isinstance(value, str) or not value.strip() or value != value.strip():
            raise ValueError('질문 연결의 비어 있거나 잘못된 항목: ' + key)
    if context['work_id'] != 'op:' + context['operation_id']:
        raise ValueError('질문 연결의 작업 식별자가 일치하지 않습니다.')
    if recording_id is not None and context['recording_id'] != recording_id:
        raise ValueError('질문 연결이 다른 녹음을 가리킵니다.')
    supported = {(field, unit, question) for (field, _), (unit, question) in QUESTIONS.items()}
    if (context['target_field'], context['expected_unit'], context['question']) not in supported:
        raise ValueError('지원하지 않거나 서로 다른 질문 항목·단위·문장입니다.')
    return dict(context)


def _detail(text):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate question detail key')
            result[key] = value
        return result
    def reject(_):
        raise ValueError('Invalid JSON number')
    if not isinstance(text, str):
        raise ValueError('Question detail must be JSON text')
    value = json.loads(text, object_pairs_hook=pairs, parse_constant=reject)
    json.dumps(value, allow_nan=False)
    if not isinstance(value, dict):
        raise ValueError('Question detail must be an object')
    return value


def build_question_context(source_id, work_id, recording, operation, events):
    """Resolve one explicit FIELD_ANSWER_CONTEXT; never guess from labels or time.

    An answer event itself carries the app's question identity. No separate
    nearest-question search or prior inferred value is needed. Multiple linked
    events are ambiguous even if their apparent text is the same.
    """
    if not isinstance(recording, dict) or not isinstance(operation, dict) or not isinstance(events, list):
        return None
    rid, sid, opid = recording.get('id'), recording.get('session_id'), recording.get('operation_id')
    if (recording.get('phase') != 'WORK' or not all(isinstance(x, str) and x for x in (rid, sid, opid))
            or operation.get('id') != opid or operation.get('session_id') != sid
            or work_id != 'op:' + opid):
        return None
    linked = [e for e in events if isinstance(e, dict) and e.get('type') == 'FIELD_ANSWER_CONTEXT'
              and e.get('recording_id') == rid]
    if len(linked) != 1:
        return None
    event = linked[0]
    if event.get('session_id') != sid or event.get('operation_id') != opid:
        return None
    try:
        detail = _detail(event.get('detail'))
        if type(detail.get('version')) is not int or detail['version'] != 1 or detail.get('phase') != 'WORK':
            return None
        field, audio = detail.get('key'), detail.get('audio')
        if not isinstance(field, str) or not isinstance(audio, str) or (field, audio) not in QUESTIONS:
            return None
        unit, question = QUESTIONS[field, audio]
        if detail.get('text') != question or detail.get('species') is not None:
            return None
        return validate_question_context({
            'version': 1, 'source_id': source_id, 'work_id': work_id,
            'recording_id': rid, 'session_id': sid, 'operation_id': opid,
            'event_id': event.get('id'), 'target_field': field,
            'expected_unit': unit, 'question': question,
        }, recording_id=rid)
    except (ValueError, TypeError):
        return None


def consumed_context(input_data):
    """Only context actually supplied to a worker participates in run identity."""
    if not isinstance(input_data, dict):
        return None
    return validate_question_context(input_data.get('question_context'),
                                     recording_id=input_data.get('recording', {}).get('id'))
