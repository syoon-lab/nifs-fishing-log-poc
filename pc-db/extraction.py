"""Assemble evidence-bearing model claims into an unconfirmed fisheries draft.

The model selects claims; this module checks literal evidence and numeric units.
It never repairs phonetic ASR errors or fills facts from an expected answer.
"""
import functools
import math
import re

import store

VERSION = 'grounded-claims/3'
ONES = {'한': 1, '하나': 1, '두': 2, '둘': 2, '세': 3, '셋': 3,
        '네': 4, '넷': 4, '다섯': 5, '여섯': 6, '일곱': 7, '여덟': 8, '아홉': 9}
TENS = {'열': 10, '스물': 20, '스무': 20, '서른': 30, '마흔': 40, '쉰': 50,
        '예순': 60, '일흔': 70, '여든': 80, '아흔': 90}
DIGITS = {letter: value for value, letter in enumerate('영일이삼사오육칠팔구')}
DIGITS['공'] = 0
# Sino tens followed by native ones, as in colloquial 오십다섯 (55).
# 세/네 are left out: 오십세 reads as an age and 네 also means yes.
MIXED_ONES = {'하나': 1, '한': 1, '둘': 2, '두': 2, '셋': 3, '넷': 4,
              '다섯': 5, '여섯': 6, '일곱': 7, '여덟': 8, '아홉': 9}
_mixed_one = '(?:' + '|'.join(sorted(MIXED_ONES, key=len, reverse=True)) + ')'


def parse_number(raw):
    """Exact Arabic/native/Sino-Korean number; unknown syllables are not corrected."""
    if not isinstance(raw, str):
        return None
    text = re.sub(r'\s+', '', raw)
    if re.fullmatch(r'(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?', text):
        number = float(text.replace(',', ''))
        return number if math.isfinite(number) and number <= 1e9 else None
    if text in ONES:
        return float(ONES[text])
    mixed = re.fullmatch('(.*십)(' + _mixed_one + ')', text)
    if mixed:
        tens = parse_number(mixed[1])
        return None if tens is None else tens + MIXED_ONES[mixed[2]]
    for tens, value in TENS.items():
        if text == tens:
            return float(value)
        if text.startswith(tens) and text[len(tens):] in ONES:
            return float(value + ONES[text[len(tens):]])
    if '점' in text:
        parts = text.split('점')
        if len(parts) != 2 or not parts[1] or any(c not in DIGITS for c in parts[1]):
            return None
        whole = parse_number(parts[0])
        return None if whole is None else whole + float('0.' + ''.join(str(DIGITS[c]) for c in parts[1]))
    if text in ('영', '공'):
        return 0.0
    if not re.fullmatch('[일이삼사오육칠팔구십백천만]+', text):
        return None
    total = section = digit = 0
    last = 10000
    previous_digit = False
    for char in text:
        if char in DIGITS:
            if previous_digit:
                return None
            digit = DIGITS[char]
            previous_digit = True
            continue
        unit = {'십': 10, '백': 100, '천': 1000, '만': 10000}[char]
        if unit == 10000:
            if total:
                return None
            total = (section + digit or 1) * 10000
            section = digit = 0
            last = 10000
        else:
            if unit >= last:
                return None
            section += (digit or 1) * unit
            digit = 0
            last = unit
        previous_digit = False
    return float(total + section + digit)


_one = '(?:' + '|'.join(sorted(ONES, key=len, reverse=True)) + ')'
_ten = '(?:' + '|'.join(sorted(TENS, key=len, reverse=True)) + ')'
_sino = (r'[영공일이삼사오육칠팔구십백천만]+(?:\s+[영공일이삼사오육칠팔구십백천만]+)*'
         r'(?:(?<=십)\s*' + _mixed_one + ')?')
_native = '(?:' + _ten + r'(?:\s*' + _one + ')?|' + _one + '|' + _sino + ')'
_korean = _native + r'(?:\s*점\s*[영공일이삼사오육칠팔구]+)?'
_arabic = r'(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?'
_PARTICLE = '(?:은|는|이|가|을|를|도|만|에서|에|의|으로|로|과|와|하고|이랑|마다|까지|부터)'
_POT = r'(?:통발|(?<![가-힣])통\s+발' + _PARTICLE + r'?(?![가-힣]))'
_NET = r'(?:자망|(?<![가-힣])자\s+망' + _PARTICLE + r'?(?![가-힣]))'
GEAR_TOKEN = {'POT': _POT, 'GILLNET': _NET}
# The omission demand and the gear context of an amount read only 통발/자망 or their spaced
# forms with a subject or object particle; 통 발에 and 통 발로 may be a bucket and a foot.
_GEAR_COVER = r'통발|자망|(?<![가-힣])(?:통\s+발|자\s+망)(?:은|는|이|가|을|를|도|만)?(?![가-힣])'
_units = r'킬로그램|킬로그람|키로그램|키로그람|킬로|키로|그램|그람|[kK][gG]|[gG]|마리|상자|박스|두름|묶음|폭|개|명|통(?!발|\s+발' + _PARTICLE + r'?(?![가-힣]))'
MEASURE = re.compile(r'(?P<number>(?<![\d.,A-Za-z])' + _arabic +
                     r'|(?<![가-힣A-Za-z\d])' + _korean + r')\s*(?P<unit>' + _units + r')')
CATCH_UNIT = {'마리', '개', '상자', '박스', '두름', '묶음', '통'}
KG_UNIT = {'kg', '킬로그램', '킬로그람', '키로그램', '키로그람', '킬로', '키로'}
G_UNIT = {'g', '그램', '그람'}
REDEPLOY = re.compile(r'재투망|재설치|다시\s*(?:투망|놓|넣|설치|내렸)')
HAUL = re.compile(r'양망|회수|걷었|걷어|걷은|건져|끌어')
NO_CATCH = re.compile(r'못\s*잡|잡지\s*못|안\s*잡|잡히지\s*않|없|무어획|영\s*마리|0\s*마리')
ALL_NO_CATCH = re.compile(r'모든\s*어종|어떤\s*어종도|아무\s*(?:것도|고기도|물고기도)')
NOT_FISHED = re.compile(r'조업(?:을|은|도)?\s*(?:안|못|하지\s*않)|미조업')
NO_NOTES = re.compile(r'(?:특이\s*사항(?:은|이|도)?|이상(?:은|이)?)\s*없(?:습니다|어요|다|음|었어요|었습니다|어|네요|네|죠)?(?![가-힣])')
_B_FINAL = ''.join(chr(0xAC00 + 28 * index + 17) for index in range(19 * 21))  # syllables ending in ㅂ
_SS_FINAL = ''.join(chr(0xAC00 + 28 * index + 20) for index in range(19 * 21))  # syllables ending in ㅆ
# Question endings added in 2026-11:
# -ㅂ니까, except the greetings 안녕하십니까 and 고생/수고 많으십니까;
# -나요 only after a past, 있/없, 맞 or 되 stem, since 고장나요 and 소리나요 are statements;
# -냐 at a word end, except the filler 뭐냐/머시냐 and the lead-ins 왜냐 하면, 어떻게 됐냐 하면.
HYPOTHETICAL = re.compile(r'예를\s*들|예시|예컨대|가정|만약|없으면|있으면|몇\s*(?:마리|개|폭)|얼마|입니까|인가요|습니까|까요|\?'
                          r'|(?<!안녕하)(?<!고생 많으)(?<!수고 많으)(?<!고생많으)(?<!수고많으)[' + _B_FINAL + r']니까'
                          r'|(?:[' + _SS_FINAL + r']|없|맞|되)나요'
                          r'|(?<=[가-힣])(?<!뭐)(?<!머)(?<!뭐시)(?<!머시)(?<!왜)'
                          r'(?<!어떻게 됐)(?<!어떻게됐)(?<!어떻게 했)(?<!어떻게했)냐(?![가-힣])')
CONDITIONAL = re.compile(r'예를\s*들|예시|예컨대|가정|만약|만일|(?<![가-힣])가령|혹시|(?<![가-힣])아마|없으면|있으면')
# The answer shape of phone 0.9.2: the asked action and gear, then one amount.
ANSWER_ACTION = {
    'haul_quantity': r'(?:걷은|거둔|양망\s*한|회수\s*한)',
    'redeploy_quantity': r'(?:(?:다시\s*)?(?:투망\s*한|설치\s*한|놓은|넣은)|재투망\s*한|재설치\s*한)',
}
ANSWER_GEAR = {'개': r'통\s*발', '폭': r'자\s*망'}
ANSWER_END = r'\s*(?:입니다|예요|이에요|이요|였어요|이었어요|요)?[\s.!]*'
# Words right before the closing answer that put it on another day, another boat or a condition.
_OTHER_DAY = (r'(?:어제|어저께|어젯|엊저녁|엊그제|그제|그저께|그끄저께|첫날|지난\s*번|지난\s*(?:주|달|해)'
              r'|저번\s*(?:주|달|해)?|작년|내일|모레|(?<![가-힣])그\s*(?:때|날)'
              r'|\d+\s*월\s*\d+\s*일|[일이삼사오육칠팔구십]+\s*월\s*[일이삼사오육칠팔구십]+\s*일'
              r'|(?<!조금\s)(?<!방금\s)(?<!금방\s)(?<!아까\s)(?<!조금)(?<!방금)(?<!금방)(?<!아까)전에)\S*'
              r'(?:\s+(?:아침|낮|저녁|밤|새벽|오전|오후)\S*)?')
# 좋으면, 걷었다면, 치면 right before it are conditions; 그러면 is 'so'.
NOT_THIS_TIME = re.compile(r'(?:(?<![가-힣])(?:안|못|덜)|' + _OTHER_DAY + r'|(?:옆|다른|남의)\s*배\S*'
                           r'|\S*(?<!그러)면)\s*$')
FUTURE = re.compile(r'예정|계획|하겠|하려고|할\s*것')
_range_number = '(?:' + _arabic + '|' + _korean + ')'
RANGE = re.compile(_range_number + r'\s*(?:' + _units + r')?\s*(?:에서|부터|내지|~|～|-)\s*' +
                   _range_number + r'\s*(?:' + _units + ')')
CORRECTION = re.compile(r'정정|아니(?:라|고|에요|다|야)?|수정(?:하면|합니다|하겠)')
POSITIVE_CATCH = re.compile(r'잡았|잡혔|어획했|포획했|건졌')
POSITIVE_DECLARATIVE = re.compile(
    r'(?:잡았|잡혔|건졌)(?:습니다|어요|네요|죠|다|어)(?![가-힣])'
    r'|(?:어획했|포획했)(?:습니다|어요|다)(?![가-힣])')
NEGATED_CATCH = re.compile(r'못|않|아니|아닙|아닌|없|안\s*잡|안\s*건|안\s*어획|안\s*포획')
WORK_PHRASE = re.compile(
    r'(?:통발|자망|그물|어구)(?:을|를)?\s*'
    r'(?:(?:회수|양망|투망|철거)(?:를|을)?\s*'
    r'(?:마쳤(?:습니다|어요|다)|끝냈(?:습니다|어요|다)|하였습니다|했습니다|했다|합니다|중입니다)'
    r'|걷기\s*시작(?:합니다|했습니다|했다)|걷(?:었습니다|었어요|었다|습니다))(?![가-힣])')
NON_WORK_DESCRIPTION = re.compile(
    r'(?:(?:이것은|이건)\s*)?(?:(?:시험용|테스트용|시연용|시험|테스트|시연|가상|연습|음성)\s*)*'
    r'(?:녹음|기록|메모|자료)(?:입니다|이에요|예요|임)?'
    r'|(?:녹음|기록)(?:을|를)?\s*(?:시작|종료|중지)(?:합니다|했습니다|하였습니다|하겠습니다|할게요|함)'
    r'|(?:시험|테스트|시연|광고|홍보)(?:입니다|이에요|예요)'
    r'|안녕하세요|안녕하십니까|반갑습니다|감사합니다|고맙습니다')


def _work_phrases(text):
    """A small set of explicit action phrases; never repair unknown ASR verbs."""
    found = []
    for match in WORK_PHRASE.finditer(text):
        quote = match.group()
        try:
            _assert_reported(text, quote)
        except ValueError:
            continue
        if any(FUTURE.search(c) or CORRECTION.search(c) or NEGATED_CATCH.search(c)
               for c in _context(text, quote)):
            continue
        if quote not in found:
            found.append(quote)
    return found


def _positive_fact_quotes(text, species):
    """Find direct species+past-catch statements without crossing another subject."""
    if not isinstance(species, str) or not species:
        return []
    pattern = re.compile(r'(?<![가-힣A-Za-z])' + re.escape(species) + r'(?:을|를|이|가|은|는)?\s*'
                         r'(?:(?:실제로|방금|오늘)\s+)?(?:' + POSITIVE_DECLARATIVE.pattern + ')')
    found = []
    for match in pattern.finditer(text):
        quote = match.group()
        contexts = _context(text, quote)
        if (not _positive_catch(quote, species) or any(
                NEGATED_CATCH.search(c) or FUTURE.search(c) or HYPOTHETICAL.search(c)
                or CORRECTION.search(c) or re.search(r'거짓|허위', c) for c in contexts)):
            continue
        if quote not in found:
            found.append(quote)
    return found


def _answer_measure(text, context):
    """Only a single explicit amount/unit answer may inherit a linked question's role."""
    if context is None:
        return None
    observed = measurements(text)
    if len(observed) != 1 or observed[0]['unit'] != context['expected_unit']:
        return None
    amount = observed[0]
    prefix, suffix = text[:amount['start']], text[amount['end']:]
    lead = (r'\s*(?:(?:네|예)[\s,.!]*)?(?:(?:이번에|오늘)\s*)?'
            r'(?:' + ANSWER_ACTION[context['target_field']] + r'\s*)?'
            r'(?:' + ANSWER_GEAR[context['expected_unit']] + r'(?:은|는|이|가)?\s*)?')
    if (not re.fullmatch(lead, prefix)
            or not re.fullmatch(ANSWER_END, suffix)
            or int(amount['value']) != amount['value']
            # '네 다섯' without a pause is usually 4-5, not 'yes, five'.
            or re.search(r'네\s+$', prefix) and amount['quote'].startswith('다섯')):
        return None
    return amount


def _positive_catch(quote, species):
    return (isinstance(species, str) and species in quote and bool(POSITIVE_DECLARATIVE.search(quote))
            and not NEGATED_CATCH.search(quote) and not FUTURE.search(quote)
            and not HYPOTHETICAL.search(quote))


def _target_species(value, text):
    cue = r'(?:목표(?:\s*어종)?|대상\s*어종)'
    forward = re.compile(cue + r'(?:은|는|이|가|을|를)?\s*[:：]?\s*' + re.escape(value))
    backward = re.compile(re.escape(value) + r'(?:을|를|이|가)?\s*' + cue)
    for pattern in (forward, backward):
        for match in pattern.finditer(text):
            following = text[match.end():match.end() + 15]
            if not re.match(r'(?:가|이|는|은)?\s*(?:아니|말고)', following):
                return True
    return False


def _overall_no_catch(quote, text):
    if ALL_NO_CATCH.search(quote):
        return True
    # A species-specific "한 마리도 못 잡았다" is not an all-species assertion.
    # Generic "어획 없음" is allowed only as the clause's actual subject, not a
    # short substring cut from "민어의 어획은 없다".
    if not re.search(r'전체\s*(?:어획|어종)|총\s*어획|어획(?:량)?|무어획', quote):
        return False
    clause_start = re.compile(r'^\s*(?:(?:오늘|이번\s*조업|이번)(?:은|도|에서|에는|엔)?\s*)?'
                              r'(?:전체\s*(?:어획|어종)|총\s*어획|어획(?:량)?|무어획)')
    return any(clause_start.search(context) for context in _context(text, quote))


NUMERAL_CHARS = frozenset('영공일이삼사오육칠팔구십백천만')


_NUMBER_GAP = ',…·-–—~～\u00ad\u200b\u200c\u200d\u2060\ufeff'
_NUMBER_FILLER = re.compile(r'(?<![가-힣])(?:어|음|아|에|으|그|저)+$')


def _numeral_word_before(text, start):
    """True when text[:start] ends in a word made only of numerals that ends in 십/백/천/만.

    An amount there may be the end of a longer spoken number (삼백 다섯, 백 스물두), so it
    is not read on its own. A particle such as 만 in 문어만 is part of a longer word.
    Spaces, commas, dashes, ellipses (also ...), zero-width characters and standalone
    fillers may stand in between (삼백, 오십다섯; 삼백 어 오십다섯). One full stop may not:
    삼백. 오십 개 can be two amounts.
    """
    end = start
    while True:
        gap = end
        while end > 0:
            if text[end - 1].isspace() or text[end - 1] in _NUMBER_GAP:
                end -= 1
            elif text[end - 1] == '.' and end > 1 and text[end - 2] == '.':
                while end > 0 and text[end - 1] == '.':
                    end -= 1
            else:
                break
        filler = _NUMBER_FILLER.search(text, max(0, end - 12), end) if end < gap else None
        if filler is None:
            break
        end = filler.start()
    if end == 0 or text[end - 1] not in '십백천만':
        return False
    begin = end
    while begin > 0 and text[begin - 1] in NUMERAL_CHARS:
        begin -= 1
    return begin == 0 or not '가' <= text[begin - 1] <= '힣'


def measurements(quote):
    values = []
    for match in MEASURE.finditer(quote):
        if not match['number'][0].isdigit() and _numeral_word_before(quote, match.start()):
            continue
        value = parse_number(match['number'])
        if value is not None:
            values.append({'value': value, 'unit': match['unit'], 'quote': match.group(),
                           'start': match.start(), 'end': match.end()})
    return values


def _context(text, quote):
    """Whole surrounding sentence, only for context checks; evidence stays exact."""
    found = []
    for match in re.finditer(re.escape(quote), text):
        start = max(text.rfind('.', 0, match.start()), text.rfind('\n', 0, match.start()),
                    text.rfind('!', 0, match.start()), text.rfind('?', 0, match.start())) + 1
        endpoints = [p for p in (text.find(c, match.end()) for c in '.\n!?') if p >= 0]
        end = min(endpoints) if endpoints else len(text)
        found.append(text[start:end])
    return found


def _sentence_spans(text, quote):
    """(sentence start, sentence end, occurrence end, occurrence start), bounds as in _context."""
    found = []
    for match in re.finditer(re.escape(quote), text):
        start = max(text.rfind('.', 0, match.start()), text.rfind('\n', 0, match.start()),
                    text.rfind('!', 0, match.start()), text.rfind('?', 0, match.start())) + 1
        endpoints = [p for p in (text.find(c, match.end()) for c in '.\n!?') if p >= 0]
        end = min(endpoints) if endpoints else len(text)
        found.append((start, end, match.end(), match.start()))
    return found


@functools.lru_cache(maxsize=256)
def _closing_answer(sentence, target_field, expected_unit):
    """(lead start, amount start, amount end, value) of a closing 'asked action + gear + amount'."""
    lead = re.compile(r'(?<!\S)(?:(?:이번에|오늘)\s*)?(?:' + ANSWER_ACTION[target_field] + r'\s*){1,2}'
                      + ANSWER_GEAR[expected_unit] + r'(?:은|는|이|가)?\s*')
    # Only the last lead can close the sentence: what follows it holds no action word.
    match = None
    for match in lead.finditer(sentence):
        pass
    if match is None:
        return None
    rest = sentence[match.end():]
    observed = measurements(rest)
    if (len(observed) == 1 and observed[0]['start'] == 0
            and observed[0]['unit'] == expected_unit
            and observed[0]['value'] == int(observed[0]['value'])
            and re.fullmatch(ANSWER_END, rest[observed[0]['end']:])
            and not NOT_THIS_TIME.search(sentence[:match.start()])):
        return match.start(), match.end(), match.end() + observed[0]['end'], observed[0]['value']
    return None


@functools.lru_cache(maxsize=1024)
def _closing_span(text, start, end, target_field, expected_unit):
    """Closing answer of the sentence text[start:end] in text offsets, or None when it may not
    stand on its own: a '?' or a conditional word in the sentence, or a correction, a range or
    another amount of the asked unit after the sentence (350개. 아니 300개)."""
    if text[end:end + 1] == '?':
        return None
    sentence = text[start:end]
    closing = _closing_answer(sentence, target_field, expected_unit)
    if closing is None or CONDITIONAL.search(sentence):
        return None
    later = text[end:]
    if (CORRECTION.search(later) or RANGE.search(later)
            or any(m['unit'] == expected_unit for m in measurements(later))):
        return None
    lead_start, amount_start, amount_end, value = closing
    return start + lead_start, start + amount_start, start + amount_end, value


def _answer_tail(text, span, context):
    """Closing answer of a linked answer recording in this occurrence's sentence, or None."""
    if (context is None or context['target_field'] not in ANSWER_ACTION
            or context['expected_unit'] not in ANSWER_GEAR):
        return None
    return _closing_span(text, span[0], span[1], context['target_field'], context['expected_unit'])


def _hypothetical_at(text, span, question_context):
    start, end, occurrence_end, occurrence_start = span
    if text[end:end + 1] == '?':
        return True
    sentence = text[start:end]
    if not HYPOTHETICAL.search(sentence):
        return False
    closing = _answer_tail(text, span, question_context)
    if closing is None:
        return True
    lead_start, amount_start, amount_end, _ = closing
    # Within the closing answer an occurrence stands on its own only if it holds the whole
    # amount: one that starts inside it (50개 of 350개) or ends before it stays in the question.
    if occurrence_start >= lead_start:
        return occurrence_start > amount_start
    return occurrence_end < amount_end


def _closing_amount(text, quote, question_context):
    """Value of the closing answer when every occurrence of quote holds it whole, else None."""
    values = set()
    for span in _sentence_spans(text, quote):
        closing = _answer_tail(text, span, question_context)
        if closing is None:
            return None
        lead_start, amount_start, amount_end, value = closing
        if not lead_start <= span[3] <= amount_start or span[2] < amount_end:
            return None
        values.add(value)
    return values.pop() if len(values) == 1 else None


def _assert_reported(text, quote, question_context=None):
    if HYPOTHETICAL.search(quote):
        raise ValueError('질문·가정 문장을 사실로 저장할 수 없습니다: ' + quote)
    spans = _sentence_spans(text, quote)
    if spans and all(_hypothetical_at(text, span, question_context) for span in spans):
        raise ValueError('질문·가정 문맥의 값을 저장할 수 없습니다: ' + quote)


def _is_reported(text, quote, question_context=None):
    try:
        _assert_reported(text, quote, question_context)
    except ValueError:
        return False
    return True


def _partial_amount(transcript, quote):
    """True when a quote opens with a number that, at every occurrence, cuts a longer amount."""
    inside = measurements(quote)
    if not inside or quote[:inside[0]['start']].strip():
        return False
    offset = inside[0]['start']
    # Every spoken amount counts here, also one that measurements() leaves unread (백 스물두).
    spoken = [{'start': m.start(), 'end': m.end()} for m in MEASURE.finditer(transcript)]
    starts = [m.start() + offset for m in re.finditer(re.escape(quote), transcript)]
    return bool(starts) and all(
        any(m['start'] < start < m['end'] for m in spoken)
        or _numeral_word_before(transcript, start)
        or re.search(r'\d[.,]?$', transcript[:start])
        for start in starts)


def _number(value, field):
    if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 1e9:
        raise ValueError(field + ': 유한한 양의 수/0만 허용합니다.')
    if field in ('workers', 'gear_quantity', 'haul_quantity', 'redeploy_quantity') and int(value) != value:
        raise ValueError(field + ': 정수가 필요합니다.')


def _numeric_evidence(field, value, quote, fields, row=None):
    _number(value, field)
    if re.search(r'\d\s*(?:~|～|에서|내지|부터)\s*\d|정정|아니라|아니고', quote):
        raise ValueError(field + ': 범위·정정 수량은 확인이 필요합니다.')
    measures = measurements(quote)
    if field == 'weight_kg':
        matching = [(m['value'] / 1000 if m['unit'].lower() in G_UNIT else m['value'])
                    for m in measures if m['unit'].lower() in KG_UNIT | G_UNIT]
    else:
        units = {'명'} if field == 'workers' else {'개', '폭'} if row is None else CATCH_UNIT
        if row is None and field != 'workers':
            if fields['gear_unit'] is not None:
                units = {fields['gear_unit']}
            elif fields['gear_type'] in ('POT', 'GILLNET'):
                units = {'개' if fields['gear_type'] == 'POT' else '폭'}
        if row is not None and row['unit']:
            units = {row['unit']}
        matching = [m['value'] for m in measures if m['unit'] in units]
    if not matching or any(not math.isclose(number, value, rel_tol=0, abs_tol=1e-8) for number in matching):
        raise ValueError(field + ': 인용문에 해당 수량과 단위가 없거나 서로 다릅니다: ' + quote)
    if field == 'quantity' and row and row['unit'] == '마리' and int(value) != value:
        raise ValueError('마리 수는 정수여야 합니다.')


def _literal(value, quote, field):
    if not isinstance(value, str) or not value.strip() or value not in quote:
        raise ValueError(field + ': 값이 인용 원문에 그대로 있어야 합니다.')


def coverage_warnings(text, fields, evidence, question_context=None):
    """Flag clear spoken information omitted by an empty or incomplete model reply."""
    warnings = []
    observed = []
    for measure in measurements(text):
        try:
            _assert_reported(text, measure['quote'], question_context)
            contexts = _context(text, measure['quote'])
            if any(RANGE.search(context) or CORRECTION.search(context) for context in contexts):
                continue
            observed.append(measure)
        except ValueError:
            pass
    if any(m['unit'] == '명' for m in observed) and re.search(r'인원|작업자|사람', text) and fields['workers'] is None:
        warnings.append('작업 인원 발화가 있지만 인원 항목이 비어 있습니다.')
    if any(m['unit'] in ('개', '폭') for m in observed) and re.search(_GEAR_COVER, text) and all(fields[k] is None for k in ('gear_quantity', 'haul_quantity', 'redeploy_quantity')):
        warnings.append('어구 수량 발화가 있지만 어구 수량 항목이 비어 있습니다.')
    numeric_evidence = {kind: [item['quote'] for item in evidence
                              if re.fullmatch(r'catches\[\d+\]\.' + kind, item['field'])]
                        for kind in ('quantity', 'weight_kg')}
    for measure in observed:
        unit = measure['unit']
        if unit.lower() in KG_UNIT | G_UNIT:
            kind = 'weight_kg'
        elif unit in CATCH_UNIT:
            if unit in ('개', '통', '묶음'):
                nearby = text[max(0, measure['start'] - 80):measure['end'] + 80]
                if not re.search(r'잡았|잡혔|어획|포획|낚았', nearby) or re.search(_POT + '|' + _NET + r'|어구', nearby):
                    continue
            kind = 'quantity'
        else:
            continue
        # "한 마리도 못 잡았다" supplies a no-catch fact, not a count of one.
        after = text[measure['end']:measure['end'] + 18]
        if kind == 'quantity' and re.match(r'\s*도\s*(?:못|없|잡지\s*못|안\s*잡)', after):
            continue
        if not any(measure['quote'] in quote for quote in numeric_evidence[kind]):
            label = '중량' if kind == 'weight_kg' else '수량'
            warning = f'어획 {label} 발화가 있지만 해당 값의 근거가 비어 있습니다: {measure["quote"]}'
            if warning not in warnings:
                warnings.append(warning)
    if fields['notes_state'] != 'EXPLICIT_NONE' and any(_is_reported(text, m.group()) for m in NO_NOTES.finditer(text)):
        warnings.append('특이사항 없음 발화가 있지만 해당 상태가 반영되지 않았습니다.')
    if fields['notes_text'] is None and re.search(r'바람|파도|고장|손상|분실|위치.{0,20}옮|작업.{0,15}중단', text) and not HYPOTHETICAL.search(text):
        warnings.append('현장 특이사항 발화가 있지만 특이사항 내용이 비어 있습니다.')
    if fields['work_type'] is None and _work_phrases(text):
        warnings.append('명시한 어구 작업 내용이 작업 항목에서 빠졌습니다.')
    # Require the model to retain a separate explicitly named no-catch row.
    # This detects an omission only; it never creates a species or numeric zero.
    no_catch_species = re.compile(
        r'(?<![가-힣])(?P<species>[가-힣]{1,20}?)(?:은|는|을|를)\s*'
        r'(?:한\s*마리도\s*(?:없|못\s*잡|잡지\s*못)|(?:전혀\s*)?(?:못\s*잡|잡지\s*못))')
    for match in no_catch_species.finditer(text):
        if match['species'] in ('저', '나', '우리', '저희', '누구', '아무', '오늘', '어제'):
            continue
        try:
            _assert_reported(text, match.group())
        except ValueError:
            continue
        if not any(row['species'] == match['species'] and row['outcome'] == 'NO_CATCH'
                   for row in fields['catches']):
            warnings.append('명시한 어종의 무어획 상태가 빠졌습니다: ' + match['species'])
    return warnings


def assemble_claims(payload, transcript, phase=None, question_context=None):
    """Return {extraction, grounding}; reject ungrounded claims instead of repairing."""
    if not isinstance(payload, dict) or set(payload) != {'claims', 'uncertainties'}:
        raise ValueError('claims/uncertainties 객체가 필요합니다.')
    if not isinstance(transcript, str):
        raise ValueError('전사 문자열이 필요합니다.')
    if question_context is not None:
        from answer_context import validate_question_context
        question_context = validate_question_context(question_context)
        if phase != 'WORK':
            raise ValueError('연결된 추가 질문은 WORK 답변 녹음에만 사용할 수 있습니다.')
    answer_measure = _answer_measure(transcript, question_context)
    claims = payload['claims']
    uncertainties = payload['uncertainties']
    if not isinstance(claims, list) or len(claims) > 500 or not isinstance(uncertainties, list) or any(not isinstance(x, str) for x in uncertainties):
        raise ValueError('추출 주장/불확실 항목 형식 오류')
    uncertainties = list(uncertainties)
    fields = store.blank_fields()
    evidence = []
    accepted = {}
    catch_indices = set()
    forbidden = {'record_class', 'deployment_date', 'notes'}
    for claim in claims:
        if not isinstance(claim, dict) or set(claim) != {'field', 'value', 'quote'}:
            raise ValueError('각 claim에는 field/value/quote만 필요합니다.')
        field, value, quote = claim['field'], claim['value'], claim['quote']
        if not isinstance(field, str) or not isinstance(quote, str) or not quote.strip() or quote not in transcript:
            raise ValueError('전사 원문과 일치하는 field/quote가 필요합니다.')
        if value is None:
            raise ValueError('값이 없는 필드는 claim을 만들지 마세요: ' + field)
        if field in accepted:
            if accepted[field]['value'] != value or type(accepted[field]['value']) is not type(value):
                raise ValueError('같은 항목의 서로 다른 주장: ' + field)
            continue
        match = re.fullmatch(r'catches\[(\d+)\]\.([a-z_]+)', field)
        if match:
            index, key = int(match[1]), match[2]
            if index > 100 or key not in store.CATCH_FIELDS:
                raise ValueError('알 수 없는 어획 항목: ' + field)
            catch_indices.add(index)
        elif field not in store.FIELDS or field in forbidden:
            raise ValueError('자동 추출할 수 없는 항목: ' + field)
        else:
            key = field
        if key in ('workers', 'gear_quantity', 'haul_quantity', 'redeploy_quantity', 'quantity', 'weight_kg'):
            _number(value, key)
        elif key == 'approximate':
            if type(value) is not bool:
                raise ValueError('approximate에는 참/거짓이 필요합니다.')
        elif not isinstance(value, str) or not value.strip():
            raise ValueError(field + ': 비어 있지 않은 문자열이 필요합니다.')
        _assert_reported(transcript, quote, question_context)
        accepted[field] = dict(claim)
    if catch_indices and catch_indices != set(range(max(catch_indices) + 1)):
        raise ValueError('어획 행 번호는 0부터 빠짐없이 이어져야 합니다.')
    normalizations = []
    rejected = []
    target = accepted.get('target_species')
    if target is not None:
        _literal(target['value'], target['quote'], 'target_species')
        if not _target_species(target['value'], transcript):
            reason = '목표 어종은 전사 근거가 불명확하여 미확인으로 남겼습니다.'
            rejected.append({'claim': accepted.pop('target_species'), 'reason': reason})
            uncertainties.append(reason + ' 원문: ' + target['quote'])
    work = accepted.get('work_type')
    if work is not None:
        _literal(work['value'], work['quote'], 'work_type')
        if NON_WORK_DESCRIPTION.fullmatch(work['value'].strip(' \t\r\n.!?')):
            reason = '녹음·시험 소개나 인사만 있는 표현은 조업 작업 내용에서 제외했습니다.'
            rejected.append({'claim': accepted.pop('work_type'), 'reason': reason})
            uncertainties.append(reason + ' 원문: ' + work['quote'])
    for index in sorted(catch_indices):
        prefix = f'catches[{index}].'
        unit_claim = accepted.get(prefix + 'unit')
        if not unit_claim or unit_claim['value'].lower() not in KG_UNIT | G_UNIT:
            continue
        weight = accepted.get(prefix + 'weight_kg')
        quantity = accepted.get(prefix + 'quantity')
        help_text = 'kg 중량은 weight_kg에 보존하고 quantity/unit 중복 주장만 제거하세요.'
        if weight is None:
            raise ValueError(help_text + ' 검증할 별도 weight_kg 주장이 없습니다.')
        _literal(unit_claim['value'], unit_claim['quote'], prefix + 'unit')
        _numeric_evidence('weight_kg', weight['value'], weight['quote'], fields)
        if quantity:
            amount = quantity['value'] / 1000 if unit_claim['value'].lower() in G_UNIT else quantity['value']
            if not math.isclose(amount, weight['value'], rel_tol=0, abs_tol=1e-8):
                raise ValueError(help_text + ' 두 중량 값이 서로 달라 자동 정리하지 않습니다.')
            _numeric_evidence('weight_kg', amount, quantity['quote'], fields)
        removed = [accepted.pop(prefix + key) for key in ('quantity', 'unit') if prefix + key in accepted]
        normalizations.append({'rule': 'remove_redundant_weight_as_count', 'kept_field': prefix + 'weight_kg',
                               'removed_claims': removed, 'reason': help_text})
    for index in sorted(catch_indices):
        fields['catches'].append({'species': None, 'quantity': None, 'unit': None,
                                  'weight_kg': None, 'outcome': 'UNCONFIRMED', 'approximate': False})
    for field, claim in accepted.items():
        match = re.fullmatch(r'catches\[(\d+)\]\.([a-z_]+)', field)
        if match:
            fields['catches'][int(match[1])][match[2]] = claim['value']
        else:
            fields[field] = claim['value']
    for field, claim in accepted.items():
        value, quote = claim['value'], claim['quote']
        match = re.fullmatch(r'catches\[(\d+)\]\.([a-z_]+)', field)
        key = match[2] if match else field
        row = fields['catches'][int(match[1])] if match else None
        if key in ('workers', 'gear_quantity', 'haul_quantity', 'redeploy_quantity', 'quantity', 'weight_kg'):
            _numeric_evidence(key, value, quote, fields, row)
            if _partial_amount(transcript, quote):
                raise ValueError(field + ': 인용문이 더 긴 수량의 뒷부분만 담고 있습니다: ' + quote)
            # The asked field may also stand on the closing answer of a linked answer recording,
            # whose action word (거둔, 놓은) the plain haul/redeploy words do not cover.
            question_supported = (question_context is not None and key == question_context['target_field'] and (
                answer_measure is not None and value == answer_measure['value'] and answer_measure['quote'] in quote
                or _closing_amount(transcript, quote, question_context) == value))
            context = ' '.join(_context(transcript, quote))
            if RANGE.search(context) or CORRECTION.search(context):
                raise ValueError(field + ': 범위·정정 발화의 일부 숫자를 단일 값으로 저장할 수 없습니다.')
            if key == 'workers' and not re.search(r'인원|작업자|작업.*명|명(?:이|이서|은|가)?\s*(?:함께\s*)?(?:통발|자망|그물)?.*(?:걷|양망|투망|작업|조업)', quote + ' ' + context):
                raise ValueError('인원 문맥이 없는 짧은 답변은 배분하지 않습니다.')
            if (key in ('gear_quantity', 'haul_quantity', 'redeploy_quantity') and
                    not re.search(_GEAR_COVER + r'|어구|그물|장비|재투망', quote + ' ' + context) and
                    not any(m['unit'] == '폭' for m in measurements(quote)) and not question_supported):
                raise ValueError('어구 문맥이 없는 수량입니다.')
            # A short number quote can refer to an unambiguous action in its own
            # sentence. Mixed haul/redeployment clauses still require explicit evidence.
            same_sentence = _context(transcript, quote)
            clear_haul = any(HAUL.search(c) and not REDEPLOY.search(c) and not FUTURE.search(c)
                             and len([m for m in measurements(c) if m['unit'] in ('개', '폭')]) == 1
                             for c in same_sentence)
            clear_redeploy = any(REDEPLOY.search(c) and not HAUL.search(c) and not FUTURE.search(c)
                                 and len([m for m in measurements(c) if m['unit'] in ('개', '폭')]) == 1
                                 for c in same_sentence)
            if key == 'haul_quantity' and (not (HAUL.search(quote) or clear_haul or question_supported) or FUTURE.search(quote)):
                raise ValueError('양망/회수 발화가 없는 수량입니다.')
            if key == 'redeploy_quantity' and (not (REDEPLOY.search(quote) or clear_redeploy or question_supported) or FUTURE.search(quote)):
                raise ValueError('재투망 발화가 없는 수량입니다.')
            if row is not None:
                species = row['species']
                if not species and not re.search(r'어획|잡|나왔|올라왔|포획|어종|고기', quote + ' ' + context):
                    raise ValueError('어획 문맥이 없는 짧은 수량 답변은 배분하지 않습니다.')
                if species:
                    contexts = _context(transcript, quote)
                    others = {c['species'] for c in fields['catches'] if isinstance(c['species'], str) and c['species'] != species}
                    if species not in quote and not any(species in c and not any(other in c for other in others) for c in contexts):
                        raise ValueError(field + ': 해당 어종과 수량의 연결 근거가 불명확합니다.')
                if key == 'weight_kg' and len(fields['catches']) > 1 and re.search(r'총|전체|합계|모두', quote):
                    raise ValueError('여러 어종의 총 중량을 한 어종에 배분할 수 없습니다.')
        elif key in ('work_type', 'target_species', 'notes_text', 'species', 'unit', 'gear_unit'):
            _literal(value, quote, field)
            if key == 'target_species' and not _target_species(value, transcript):
                raise ValueError('목표/대상 어종이라고 명시한 해당 어종만 target_species에 저장하세요.')
            if key == 'species':
                contexts = _context(transcript, quote)
                if contexts and all('목표' in c and not any(m['unit'] in CATCH_UNIT or m['unit'].lower() in KG_UNIT | G_UNIT for m in measurements(c)) for c in contexts):
                    raise ValueError('목표 어종을 실제 어획 어종으로 간주할 수 없습니다.')
            if key == 'unit' and value not in CATCH_UNIT:
                raise ValueError('지원되지 않는 어획 수량 단위: ' + value)
            if key == 'gear_unit' and value not in ('개', '폭'):
                raise ValueError('어구 단위는 개/폭만 허용합니다.')
            if key == 'notes_text' and re.search(r'목표\s*어종|작업\s*인원', quote) and not re.search(r'특이|바람|파도|고장|손상|분실|옮|중단', quote):
                raise ValueError('목표 어종·작업 인원 자체를 특이사항으로 저장하지 않습니다.')
        elif key == 'gear_type':
            token = GEAR_TOKEN.get(value)
            if token is None or not re.search(token, quote) or re.search(r'아니|아닌', quote):
                raise ValueError('통발/자망 원문 근거가 없는 어법입니다.')
        elif key == 'disposition':
            if value == 'REDEPLOYED':
                supported = bool(REDEPLOY.search(quote))
            elif value == 'REMOVED':
                supported = '철거' in quote
            else:
                supported = False
            if not supported or FUTURE.search(quote):
                raise ValueError('단순 회수·양망은 최종 철거/재투망 근거가 아닙니다. 명시한 처리 없으면 disposition claim을 제외하세요.')
        elif key == 'notes_state':
            if value == 'EXPLICIT_NONE' and NO_NOTES.search(quote):
                pass
            elif value == 'REPORTED' and fields['notes_text'] is not None:
                pass
            else:
                raise ValueError('특이사항 상태의 원문 근거가 없습니다.')
        elif key == 'outcome':
            if value == 'CAUGHT':
                target_rows = [row] if row is not None else fields['catches']
                numeric_support = any((c['quantity'] or 0) > 0 or (c['weight_kg'] or 0) > 0 for c in target_rows)
                supported_quotes = [fact for c in target_rows for fact in _positive_fact_quotes(transcript, c['species'])
                                    if quote in fact or fact in quote]
                spoken_support = bool(supported_quotes)
                if not numeric_support and not spoken_support:
                    raise ValueError('양의 어획 수량·중량 또는 명시적인 실제 어획 발화가 필요합니다.')
                if not numeric_support and spoken_support and not any(c['species'] in quote for c in target_rows if c['species']):
                    # Preserve the original narrow model quote in accepted_claims/raw;
                    # display the exact full species+verb phrase as validated evidence.
                    quote = supported_quotes[0]
            elif value == 'NO_CATCH':
                if not NO_CATCH.search(quote) or row is None and not _overall_no_catch(quote, transcript):
                    raise ValueError('전체 무어획과 특정 어종 무어획의 범위를 확인하세요.')
                if row is not None and row['species'] and row['species'] not in quote:
                    raise ValueError('무어획 인용문에 해당 어종이 없습니다.')
            elif value == 'NOT_FISHED':
                if row is not None or not NOT_FISHED.search(quote):
                    raise ValueError('NOT_FISHED는 조업을 안 했다/못 했다/하지 않았다는 명시적 근거가 필요합니다. '
                                     '시험·시연·가상 녹음이라는 설명은 미조업 근거가 아닙니다. '
                                     '이 NOT_FISHED 주장을 제거하고 뒤에 실제로 말한 작업·어종·어획 사실을 각각 추출하세요.')
            else:
                raise ValueError('어획 상태의 원문 근거가 없습니다.')
        elif key == 'approximate':
            if type(value) is not bool or value and not re.search(r'약|대략|정도|쯤|가량', quote):
                raise ValueError('근삿값 표시의 원문 근거가 없습니다.')
        evidence.append({'field': field, 'quote': quote})
    derived = []
    def field_quote(field):
        return next((item['quote'] for item in evidence if item['field'] == field),
                    accepted.get(field, {}).get('quote'))
    def derive(field, value, source_field):
        source_quote = field_quote(source_field)
        evidence.append({'field': field, 'quote': source_quote})
        derived.append({'field': field, 'value': value, 'from_field': source_field, 'quote': source_quote})
    def derive_rule(field, value, quote, rule):
        evidence.append({'field': field, 'quote': quote})
        derived.append({'field': field, 'value': value, 'from_rule': rule, 'quote': quote})
    work_phrases = _work_phrases(transcript)
    if fields['work_type'] is None and len(work_phrases) == 1:
        fields['work_type'] = work_phrases[0]
        derive_rule('work_type', work_phrases[0], work_phrases[0], 'explicit_gear_action')
    if answer_measure is not None:
        target_field = question_context['target_field']
        if fields[target_field] is None:
            fields[target_field] = int(answer_measure['value'])
            derive_rule(target_field, fields[target_field], answer_measure['quote'], 'linked_question_answer')
        if fields['gear_unit'] is None:
            fields['gear_unit'] = answer_measure['unit']
            derive_rule('gear_unit', answer_measure['unit'], answer_measure['quote'], 'spoken_answer_unit')
    if fields['gear_type'] is None and fields['work_type'] is not None:
        source = field_quote('work_type')
        kinds = [kind for kind, token in (('POT', '통발'), ('GILLNET', '자망')) if token in source]
        if len(kinds) == 1 and not re.search(r'아니|아닌', source):
            fields['gear_type'] = kinds[0]
            derive('gear_type', kinds[0], 'work_type')
    if fields['gear_unit'] is None:
        for key in ('gear_quantity', 'haul_quantity', 'redeploy_quantity'):
            if key in accepted:
                units = {m['unit'] for m in measurements(accepted[key]['quote']) if m['unit'] in ('개', '폭')}
                if len(units) == 1:
                    fields['gear_unit'] = next(iter(units))
                    derive('gear_unit', fields['gear_unit'], key)
                    break
    if fields['notes_state'] == 'NOT_MENTIONED':
        for match in NO_NOTES.finditer(transcript):
            quote = match.group()
            try:
                _assert_reported(transcript, quote)
            except ValueError:
                continue
            fields['notes_state'] = 'EXPLICIT_NONE'
            evidence.append({'field': 'notes_state', 'quote': quote})
            derived.append({'field': 'notes_state', 'value': 'EXPLICIT_NONE',
                            'from_rule': 'explicit_no_notes', 'quote': quote})
            break
    for index, row in enumerate(fields['catches']):
        if row['quantity'] is not None and row['unit'] is None:
            path = f'catches[{index}].quantity'
            units = {m['unit'] for m in measurements(accepted[path]['quote']) if m['unit'] in CATCH_UNIT}
            if len(units) == 1:
                row['unit'] = next(iter(units))
                derive(f'catches[{index}].unit', row['unit'], path)
        if row['outcome'] == 'UNCONFIRMED':
            amount = next((key for key in ('quantity', 'weight_kg') if (row[key] or 0) > 0), None)
            if amount:
                row['outcome'] = 'CAUGHT'
                derive(f'catches[{index}].outcome', 'CAUGHT', f'catches[{index}].{amount}')
            else:
                spoken = _positive_fact_quotes(transcript, row['species'])
                if len(spoken) == 1:
                    row['outcome'] = 'CAUGHT'
                    derive_rule(f'catches[{index}].outcome', 'CAUGHT', spoken[0], 'explicit_species_catch')
        if row['outcome'] == 'NO_CATCH' and ((row['quantity'] or 0) > 0 or (row['weight_kg'] or 0) > 0):
            raise ValueError('무어획과 양의 어획량이 충돌합니다.')
    if fields['outcome'] == 'UNCONFIRMED':
        amount = next((f'catches[{i}].{key}' for i, row in enumerate(fields['catches'])
                       for key in ('quantity', 'weight_kg') if (row[key] or 0) > 0), None)
        if amount:
            fields['outcome'] = 'CAUGHT'
            derive('outcome', 'CAUGHT', amount)
        else:
            source = next((f'catches[{i}].outcome' for i, row in enumerate(fields['catches'])
                           if row['outcome'] == 'CAUGHT'), None)
            if source:
                fields['outcome'] = 'CAUGHT'
                derive('outcome', 'CAUGHT', source)
    if fields['notes_text'] is not None and fields['notes_state'] == 'NOT_MENTIONED':
        fields['notes_state'] = 'REPORTED'
        derive('notes_state', 'REPORTED', 'notes_text')
    if fields['notes_state'] == 'EXPLICIT_NONE' and fields['notes_text'] is not None:
        raise ValueError('특이사항 없음과 내용이 충돌합니다.')
    if fields['outcome'] in ('NO_CATCH', 'NOT_FISHED') and any(c['outcome'] == 'CAUGHT' for c in fields['catches']):
        raise ValueError('전체 무어획/미조업과 양의 어획량이 충돌합니다.')
    if fields['disposition'] == 'REMOVED' and (fields['redeploy_quantity'] or 0) > 0:
        raise ValueError('철거와 재투망 수량이 충돌합니다.')
    if fields['gear_type'] in ('POT', 'GILLNET') and fields['gear_unit'] not in (None, '개' if fields['gear_type'] == 'POT' else '폭'):
        raise ValueError('어법과 어구 단위가 충돌합니다.')
    # Repeated speech is retained; duplicating one spoken amount into two rows is not.
    duplicate_rows = {}
    for index, row in enumerate(fields['catches']):
        signature = store.canonical(row)
        duplicate_rows.setdefault(signature, []).append(index)
    for indices in duplicate_rows.values():
        if len(indices) < 2:
            continue
        anchors = []
        for index in indices:
            for key in ('quantity', 'weight_kg', 'species'):
                path = f'catches[{index}].{key}'
                if path in accepted:
                    anchors.append(accepted[path]['quote'])
                    break
        if anchors and len(set(anchors)) == 1 and transcript.count(anchors[0]) < len(indices):
            raise ValueError('한 번 말한 어획을 여러 행으로 복제할 수 없습니다.')
    if fields['work_type'] is not None and not any(p in fields['work_type'] for p in work_phrases):
        uncertainties.append('작업 내용의 전사 표현을 확인하세요: ' + fields['work_type'])
    if (re.search(_POT + '|' + _NET + r'|그물|어구', transcript) and
            all(fields[k] is None for k in ('gear_quantity', 'haul_quantity', 'redeploy_quantity')) and
            not any(m['unit'] in ('개', '폭') for m in measurements(transcript))):
        uncertainties.append('어구 수량을 확인할 수 없습니다. 전사에 명확한 수량·단위가 없어 미확인으로 남겼습니다.')
    result = {'status': 'DRAFT', 'fields': fields, 'evidence': evidence, 'uncertainties': list(dict.fromkeys(uncertainties))}
    return {'extraction': result, 'grounding': {'version': VERSION, 'accepted_claims': list(accepted.values()),
            'derived': derived, 'normalizations': normalizations, 'rejected_claims': rejected,
            'question_context': question_context,
            'coverage_warnings': coverage_warnings(transcript, fields, evidence, question_context)}}


assemble = assemble_claims
