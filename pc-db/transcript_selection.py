"""Choose one complete transcript, retaining failed dialect output separately."""


def meaningful(text):
    return isinstance(text, str) and any(char.isalnum() for char in text)


def select_transcript(result, dialect_expected):
    base_ok = meaningful(result.get('asr_text'))
    dialect_ok = meaningful(result.get('dialect_text')) and not result.get('dialect_error')
    dialect_status = ('NOT_REQUESTED' if not dialect_expected else
                      'FAILED' if result.get('dialect_error') else
                      'COMPLETED' if dialect_ok else 'EMPTY')
    result['transcription_status'] = {'base': 'COMPLETED' if base_ok else 'EMPTY',
                                      'dialect': dialect_status}
    result['fallback_reason'] = None
    if dialect_expected and dialect_ok:
        result['text_source'] = 'dialect'
    elif dialect_expected and base_ok:
        result['text_source'] = 'base_fallback'
        result['fallback_reason'] = 'dialect_error' if result.get('dialect_error') else 'dialect_empty'
    else:
        result['text_source'] = 'base'
    return extraction_text(result)


def extraction_text(result):
    """Respect explicit provenance; only legacy results infer the selected source."""
    source = result.get('text_source')
    if source == 'reviewed':
        from transcript_review import reviewed_text
        return reviewed_text(result)
    if source is None:
        return result.get('dialect_text') if result.get('dialect_text') is not None else result.get('asr_text', '')
    if source == 'dialect':
        if result.get('dialect_error') or not meaningful(result.get('dialect_text')):
            raise ValueError('선택된 방언 전사에 유효한 내용이 없습니다.')
        return result['dialect_text']
    if source == 'base_fallback':
        expected = 'dialect_error' if result.get('dialect_error') else 'dialect_empty'
        if (result.get('fallback_reason') != expected or not meaningful(result.get('asr_text')) or
                meaningful(result.get('dialect_text')) and not result.get('dialect_error')):
            raise ValueError('기본 전사 대체 사유 또는 전사 선택이 올바르지 않습니다.')
        return result['asr_text']
    if source == 'base':
        if meaningful(result.get('dialect_text')) and not result.get('dialect_error'):
            raise ValueError('유효한 방언 전사를 기본 전사로 임의 변경할 수 없습니다.')
        return result.get('asr_text', '')
    raise ValueError('알 수 없는 추출 전사 출처')
