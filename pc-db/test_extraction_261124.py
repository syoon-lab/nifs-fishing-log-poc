"""2026-11-24 rule test (second set): field transcripts of 2026-11-23 and review cases.

Field transcripts are the PC Whisper outputs (base and Gyeongsang) of the same recordings.
Expected values come from what is spoken in each transcript, not from the confirmed record.
The review cases are the ones the adversarial review used against the first rule set.
2026-11-25: the particle 만 case was added before the set went to production.
"""
import unittest

import extraction

BASE_HAUL = ('어종원 문어 오늘 통발은 몇개입니까 물어봐서 그럼 몇개 55개를 하면 안되겠네 '
             '네지 350개 오늘 거둔 양망한 통발은 350개')
DIALECT_HAUL = ('어종원 문어 아유 잠깐만요 오늘 통발은 몇 개입니까 물어봐서 좀 그럼 몇 개 오십다섯 개를 '
                '하면 안 되겠네 몇 개를 해야지 그럼 몇 개를 할까 오늘 거둔 양망 한 통발은 삼백오십 개')
BASE_RESET = '투망 한 통발은 삼백 오십개입니다.'
DIALECT_RESET = '투망 한 통 발은 삼백오십 개입니다.'
QUESTIONS = {('haul_quantity', '개'): '이번에 걷은 통발은 몇 개입니까?',
             ('haul_quantity', '폭'): '이번에 걷은 자망은 몇 폭입니까?',
             ('redeploy_quantity', '개'): '다시 놓은 통발은 몇 개입니까?',
             ('redeploy_quantity', '폭'): '다시 놓은 자망은 몇 폭입니까?'}


def claim(field, value, quote):
    return {'field': field, 'value': value, 'quote': quote}


def context(target='haul_quantity', unit='개'):
    return {'version': 1, 'source_id': 'source', 'work_id': 'op:operation', 'recording_id': 'answer',
            'session_id': 'session', 'operation_id': 'operation', 'event_id': 'event',
            'target_field': target, 'expected_unit': unit, 'question': QUESTIONS[(target, unit)]}


def assemble(text, *claims, context=None):
    return extraction.assemble_claims({'claims': list(claims), 'uncertainties': []}, text,
                                      phase='WORK', question_context=context)


def fields(text, *claims, context=None):
    return assemble(text, *claims, context=context)['extraction']['fields']


class FieldTranscripts(unittest.TestCase):
    def test_closing_answer_of_a_linked_answer_is_kept(self):
        for text, quote in [(BASE_HAUL, '거둔 양망한 통발은 350개'), (BASE_HAUL, '양망한 통발은 350개'),
                            (DIALECT_HAUL, '양망 한 통발은 삼백오십 개')]:
            with self.subTest(text=text, quote=quote):
                result = fields(text, claim('gear_type', 'POT', '통발'), claim('haul_quantity', 350, quote),
                                context=context())
                self.assertEqual((result['gear_type'], result['haul_quantity'], result['gear_unit']), ('POT', 350, '개'))

    def test_without_the_linked_question_the_whole_sentence_rule_stays(self):
        with self.assertRaisesRegex(ValueError, '질문·가정'):
            assemble(BASE_HAUL, claim('haul_quantity', 350, '양망한 통발은 350개'))

    def test_number_before_the_closing_answer_stays_blocked(self):
        for text, quote in [(BASE_HAUL, '55개를 하면'), (DIALECT_HAUL, '오십다섯 개를'),
                            (BASE_HAUL.replace('그럼 몇개 ', '그럼 '), '55개를'),
                            (BASE_HAUL, '네지 350개')]:
            for linked in (None, context()):
                with self.subTest(quote=quote, linked=bool(linked)), self.assertRaisesRegex(ValueError, '질문·가정'):
                    assemble(text, claim('gear_quantity', 55 if '5' in quote or '다섯' in quote else 350, quote),
                             context=linked)

    def test_omitting_the_closing_answer_is_flagged(self):
        warnings = assemble(BASE_HAUL, context=context())['grounding']['coverage_warnings']
        self.assertTrue(any('어구 수량' in item for item in warnings))

    def test_action_prefixed_answer_fills_only_the_asked_field(self):
        for text in (BASE_RESET, DIALECT_RESET):
            with self.subTest(text=text):
                result = assemble(text, claim('gear_type', 'POT', '통발' if '통발' in text else '통 발'),
                                  context=context('redeploy_quantity'))
                drafted = result['extraction']['fields']
                self.assertEqual((drafted['redeploy_quantity'], drafted['haul_quantity']), (350, None))
                self.assertEqual(drafted['disposition'], 'UNCONFIRMED')
                self.assertIn('linked_question_answer', [d.get('from_rule') for d in result['grounding']['derived']])
                self.assertIsNone(fields(text, context=context('haul_quantity'))['haul_quantity'])


class ClosingAnswerGuards(unittest.TestCase):
    def blocked(self, text, field, value, quote, target='haul_quantity'):
        with self.subTest(text=text), self.assertRaises(ValueError):
            assemble(text, claim(field, value, quote), context=context(target))

    def test_conditional_cue_keeps_the_whole_sentence(self):
        self.blocked('만약 바람이 불면 몇 개입니까 걷은 통발은 300개', 'haul_quantity', 300, '걷은 통발은 300개')

    def test_negated_other_time_or_hedged_answers_are_not_exempt(self):
        self.blocked('다시 놓은 통발은 몇 개입니까 안 놓은 통발은 50개', 'redeploy_quantity', 50,
                     '놓은 통발은 50개', 'redeploy_quantity')
        self.blocked('이번에 걷은 통발은 몇 개입니까 어제 걷은 통발은 300개', 'haul_quantity', 300, '걷은 통발은 300개')
        self.blocked('이번에 걷은 통발은 몇 개입니까 걷은 통발은 350개 정도', 'haul_quantity', 350, '걷은 통발은 350개')
        self.blocked('이번에 걷은 통발은 몇 개입니까 걷은 통발은 350개?', 'haul_quantity', 350, '걷은 통발은 350개')
        self.blocked('이번에 걷은 통발은 몇 개입니까 글쎄요 350개쯤 될걸요', 'haul_quantity', 350, '350개쯤')


class QuestionsStayQuestions(unittest.TestCase):
    """Cases the review used against the first rule set; all must stay blocked."""

    def test_review_cases_are_blocked(self):
        for text, field, value, quote in [
                ('통발 350개 걷었다 치고 몇 개 남았습니까', 'haul_quantity', 350, '통발 350개 걷었다'),
                ('통발 300개 걷었다 그러면 몇 개 남았어요', 'haul_quantity', 300, '통발 300개 걷었다'),
                ('문어 스무 마리 잡았다 하면 얼마나 됩니까', 'catches[0].quantity', 20, '스무 마리'),
                ('통발 350개요 다시 놓을까요', 'redeploy_quantity', 350, '350개'),
                ('어제 넣은 통발 350개요 그중에 몇 개 걷었습니까 절반 걷었습니다', 'haul_quantity', 350, '350개'),
                ('선장님 통발 몇 개 걷었어요 글쎄요 350개쯤 될걸요', 'haul_quantity', 350, '350개쯤'),
                ('오늘 통발 몇 개 걷었지 350개였나', 'haul_quantity', 350, '350개'),
                ('통발 몇 개 걷었습니까 350개 됩니까', 'haul_quantity', 350, '350개'),
                ('통발 몇 개 다시 놓을까요 200개 놓겠습니다', 'redeploy_quantity', 200, '200개')]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                assemble(text, claim(field, value, quote))

    def test_question_endings_the_original_list_missed(self):
        for text in ['통발 300개 걷은 게 맞나요', '통발 300개 걷은 게 맞나요?', '통발 300개 걷었어요?',
                     '통발 300개 다 회수합니까', '통발 300개 걷었냐']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                assemble(text, claim('haul_quantity', 300, '통발 300개'))
        self.assertIsNone(extraction.HYPOTHETICAL.search('문어 많이 나요 통발 하나요'))

    def test_question_numbers_are_not_demanded_on_retry(self):
        for text in ['오늘 통발 몇 개 걷었지 350개였나', '통발 350개 걷었다고요? 그게 사실입니까?',
                     '특이사항 없죠? 맞습니까?']:
            with self.subTest(text=text):
                result = assemble(text)
                self.assertEqual(result['grounding']['coverage_warnings'], [])
                self.assertEqual(result['extraction']['fields']['notes_state'], 'NOT_MENTIONED')


class Numbers(unittest.TestCase):
    def test_mixed_numerals(self):
        for raw, value in [('오십다섯', 55), ('삼백오십다섯', 355), ('이십한', 21), ('오십 다섯', 55)]:
            self.assertEqual(extraction.parse_number(raw), value, raw)
        self.assertIsNone(extraction.parse_number('오십세'))
        self.assertIsNone(extraction.parse_number('오십네'))

    def test_spaced_amounts_are_read_whole_or_not_at_all(self):
        read = lambda text: [(m['value'], m['unit']) for m in extraction.measurements(text)]
        self.assertEqual(read('통발 삼백 오십다섯 개'), [(355.0, '개')])
        self.assertEqual(read('오십 다섯 개'), [(55.0, '개')])
        self.assertEqual(read('삼백 다섯 개'), [])
        self.assertEqual(read('백 스물두 마리'), [])
        self.assertEqual(read(BASE_RESET), [(350.0, '개')])
        self.assertEqual(read(DIALECT_RESET), [(350.0, '개')])
        self.assertEqual(read('문어 한 통이요'), [(1.0, '통')])
        self.assertEqual(read('한 통발에 문어 두 마리'), [(2.0, '마리')])

    def test_particle_man_is_not_a_numeral_word(self):
        """Added 2026-11-25: the second set hid every amount after a word ending in 십/백/천/만."""
        read = lambda text: [(m['value'], m['unit']) for m in extraction.measurements(text)]
        self.assertEqual(read('문어만 세 마리 잡았습니다'), [(3.0, '마리')])
        self.assertEqual(read('통발만 오십 개 걷었습니다'), [(50.0, '개')])
        self.assertEqual(read('오늘만 다섯 마리 잡았습니다'), [(5.0, '마리')])
        self.assertEqual(read('삼백  다섯 개'), [])
        drafted = fields('문어만 세 마리 잡았습니다', claim('catches[0].species', '문어', '문어만 세 마리 잡았습니다'),
                         claim('catches[0].quantity', 3, '세 마리'))
        self.assertEqual((drafted['catches'][0]['species'], drafted['catches'][0]['quantity']), ('문어', 3))
        drafted = fields('통발만 오십 개 걷었습니다', claim('gear_type', 'POT', '통발'), claim('haul_quantity', 50, '오십 개'))
        self.assertEqual(drafted['haul_quantity'], 50)
        with self.assertRaisesRegex(ValueError, '뒷부분'):
            assemble('통발 삼백 다섯 개 걷었습니다.', claim('haul_quantity', 5, '다섯 개'))

    def test_quote_cut_from_a_longer_amount_is_rejected(self):
        for text, value, quote in [('통발 삼백 오십다섯 개 걷었습니다.', 55, '오십다섯 개'),
                                   ('통발 삼백 오십개 걷었습니다.', 50, '오십개'),
                                   ('통발 1350개 걷었습니다.', 350, '350개')]:
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, '뒷부분'):
                assemble(text, claim('haul_quantity', value, quote))
        self.assertEqual(fields('통발 삼백 오십다섯 개 걷었습니다.',
                                claim('haul_quantity', 355, '삼백 오십다섯 개'))['haul_quantity'], 355)

    def test_yes_followed_by_five_is_not_read_as_an_answer(self):
        self.assertIsNone(fields('네 다섯 개요', context=context())['haul_quantity'])
        self.assertEqual(fields('네, 다섯 폭이요', context=context('haul_quantity', '폭'))['haul_quantity'], 5)


class GearWords(unittest.TestCase):
    def test_spaced_gear_word_counts(self):
        drafted = fields('통 발 서른 개를 걷었습니다', claim('gear_type', 'POT', '통 발'),
                         claim('haul_quantity', 30, '통 발 서른 개를 걷었습니다'))
        self.assertEqual((drafted['gear_type'], drafted['haul_quantity']), ('POT', 30))

    def test_neighbouring_words_are_not_gear_words(self):
        for text, value, quote in [('보통 발생하는 문제입니다', 'POT', '보통 발생'),
                                   ('어상자 망가진 것 버렸습니다', 'GILLNET', '어상자 망가진')]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                assemble(text, claim('gear_type', value, quote))


class ReviewFixes261125(unittest.TestCase):
    """Regressions the 2026-11-25 independent review found before the rules went to production."""

    def test_greetings_fillers_and_statements_are_not_questions(self):
        for text in ['안녕하십니까 오늘 통발 삼백오십 개 걷었습니다', '선장님 고생 많으십니까 오늘 통발 삼백오십 개 걷었습니다',
                     '오늘 그 뭐냐 통발 삼백오십 개 걷었습니다', '어 머시냐 통발 삼백오십 개 걷었습니다',
                     '오늘 조업이 어떻게 됐냐 하면 통발 삼백오십 개 걷었습니다', '왜냐 하면 바람이 불어서 통발 삼백오십 개 걷었습니다',
                     '양망기가 자꾸 고장나요 그래서 통발 삼백오십 개 걷었습니다', '엔진에서 소리나요 통발 삼백오십 개 걷었습니다',
                     '그물에 구멍나요 통발 삼백오십 개 걷었습니다']:
            with self.subTest(text=text):
                self.assertEqual(fields(text, claim('haul_quantity', 350, '통발 삼백오십 개 걷었습니다'))['haul_quantity'], 350)

    def test_questions_with_the_same_endings_stay_blocked(self):
        for text in ['선장님 통발 삼백오십 개 다시 놓으십니까', '통발 삼백오십 개 다 걷었냐', '통발 삼백오십 개 걷었냐 하면 모르겠습니다',
                     '통발 삼백오십 개 걷은 게 맞나요', '통발 삼백오십 개 걷었나요', '통발 삼백오십 개 다 있나요', '통발 삼백오십 개 되나요']:
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, '질문·가정'):
                assemble(text, claim('gear_quantity', 350, '통발 삼백오십 개'))

    def test_closing_answer_supports_the_asked_field_without_plain_action_words(self):
        text = '문어 스물다섯 마리 잡았습니다. 오늘 통발은 몇 개입니까 물어봐서 오늘 거둔 통발은 350개'
        drafted = fields(text, claim('catches[0].species', '문어', '문어 스물다섯 마리 잡았습니다'),
                         claim('catches[0].quantity', 25, '문어 스물다섯 마리'),
                         claim('haul_quantity', 350, '거둔 통발은 350개'), context=context())
        self.assertEqual((drafted['haul_quantity'], drafted['catches'][0]['quantity']), (350, 25))
        drafted = fields('오늘 몇 개 놓았냐고 물어봐서 오늘 놓은 통발은 삼백오십 개',
                         claim('redeploy_quantity', 350, '놓은 통발은 삼백오십 개'), context=context('redeploy_quantity'))
        self.assertEqual(drafted['redeploy_quantity'], 350)
        with self.assertRaises(ValueError):
            assemble(text, claim('redeploy_quantity', 350, '거둔 통발은 350개'), context=context())

    def test_closing_answer_on_another_day_or_boat_is_not_exempt(self):
        for text, field, quote in [
                ('이번에 걷은 통발은 몇 개입니까 오늘은 바람 때문에 못 걷었고 어저께 걷은 통발은 300개입니다',
                 'haul_quantity', '어저께 걷은 통발은 300개'),
                ('이번에 걷은 통발은 몇 개입니까 오늘 걷은 통발은 350개 어제 저녁에 걷은 통발은 300개',
                 'haul_quantity', '어제 저녁에 걷은 통발은 300개'),
                ('다시 놓은 통발은 몇 개입니까 지난 번에 놓은 통발은 300개입니다', 'redeploy_quantity', '지난 번에 놓은 통발은 300개'),
                ('다시 놓은 통발은 몇 개입니까 11월 3일날 투망 한 통발은 300개', 'redeploy_quantity', '투망 한 통발은 300개'),
                ('이번에 걷은 통발은 몇 개입니까 우리 배가 걷은 통발은 350개 옆 배가 걷은 통발은 300개',
                 'haul_quantity', '옆 배가 걷은 통발은 300개'),
                ('이번에 걷은 통발은 몇 개입니까 전에 걷은 통발은 300개입니다', 'haul_quantity', '걷은 통발은 300개'),
                ('이번에 걷은 통발은 몇 개입니까 첫날 걷은 통발은 300개입니다', 'haul_quantity', '걷은 통발은 300개'),
                ('이번에 걷은 통발은 몇 개입니까 저번 주에 걷은 통발은 300개입니다', 'haul_quantity', '걷은 통발은 300개'),
                ('이번에 걷은 통발은 몇 개입니까 몇 개월 전에 걷은 통발은 300개입니다', 'haul_quantity', '걷은 통발은 300개'),
                ('이번에 걷은 통발은 몇 개입니까 그때 걷은 통발은 300개입니다', 'haul_quantity', '걷은 통발은 300개')]:
            with self.subTest(text=text):
                with self.assertRaisesRegex(ValueError, '질문·가정'):
                    assemble(text, claim(field, 300, quote), context=context(field))
                warnings = assemble(text, context=context(field))['grounding']['coverage_warnings']
                self.assertFalse(any('어구 수량' in item for item in warnings))
        for lead in ('방금', '조금 전에', '오늘 아침에'):
            with self.subTest(lead=lead):
                self.assertEqual(fields(f'이번에 걷은 통발은 몇 개입니까 {lead} 걷은 통발은 300개',
                                        claim('haul_quantity', 300, '걷은 통발은 300개'), context=context())['haul_quantity'], 300)

    def test_a_dropped_number_sharing_digits_with_the_answer_stays_blocked(self):
        text = BASE_HAUL.replace('55개', '50개')
        with self.assertRaisesRegex(ValueError, '질문·가정'):
            assemble(text, claim('haul_quantity', 350, '양망한 통발은 350개'), claim('gear_quantity', 50, '50개'), context=context())
        self.assertEqual(fields(text, claim('haul_quantity', 350, '양망한 통발은 350개'), context=context())['haul_quantity'], 350)

    def test_a_correction_after_the_closing_answer_keeps_it_in_the_question(self):
        with self.assertRaisesRegex(ValueError, '질문·가정'):
            assemble('이번에 걷은 통발은 몇 개입니까 걷은 통발은 350개. 아니 300개입니다.',
                     claim('haul_quantity', 350, '걷은 통발은 350개'), context=context())
        self.assertEqual(fields('이번에 걷은 통발은 몇 개입니까 걷은 통발은 350개입니다. 수고하셨습니다.',
                                claim('haul_quantity', 350, '걷은 통발은 350개'), context=context())['haul_quantity'], 350)

    def test_hedged_or_conditional_closing_answers_are_not_exempt(self):
        for text in ['이번에 걷은 통발은 몇 개입니까 만일 다 걷었으면 걷은 통발은 350개',
                     '이번에 걷은 통발은 몇 개입니까 혹시 걷은 통발은 350개',
                     '이번에 걷은 통발은 몇 개입니까 아마 걷은 통발은 350개요',
                     '이번에 걷은 통발은 몇 개입니까 다 걷었다면 걷은 통발은 350개',
                     '이번에 걷은 통발은 몇 개입니까 바람만 안 불었으면 걷은 통발은 350개']:
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, '질문·가정'):
                assemble(text, claim('haul_quantity', 350, '걷은 통발은 350개'), context=context())
        with self.assertRaisesRegex(ValueError, '질문·가정'):
            assemble('다시 놓은 통발은 몇 개입니까 날씨 좋으면 다시 놓은 통발은 300개입니다',
                     claim('redeploy_quantity', 300, '다시 놓은 통발은 300개'), context=context('redeploy_quantity'))
        self.assertEqual(fields('이번에 걷은 통발은 몇 개입니까 그러면 걷은 통발은 350개',
                                claim('haul_quantity', 350, '걷은 통발은 350개'), context=context())['haul_quantity'], 350)

    def test_amounts_split_by_a_pause_are_not_read_in_part(self):
        read = lambda text: [(m['value'], m['unit']) for m in extraction.measurements(text)]
        for text, value, quote in [('통발 삼백, 오십다섯 개 걷었습니다', 55, '오십다섯 개 걷었습니다'),
                                   ('통발 삼백 어 오십다섯 개 걷었습니다', 55, '오십다섯 개 걷었습니다'),
                                   ('통발 삼백… 오십다섯 개 걷었습니다', 55, '오십다섯 개 걷었습니다'),
                                   ('통발 삼백... 오십다섯 개 걷었습니다', 55, '오십다섯 개 걷었습니다'),
                                   ('통발 삼백​오십다섯 개 걷었습니다', 55, '오십다섯 개 걷었습니다'),
                                   ('통발 삼백 음 어 오십 개 걷었습니다', 50, '오십 개 걷었습니다')]:
            with self.subTest(text=text):
                self.assertEqual(read(text), [])
                with self.assertRaisesRegex(ValueError, '뒷부분'):
                    assemble(text, claim('haul_quantity', value, quote))
        self.assertEqual(read('걷은 건 삼백. 오십 개는 다시 놓았습니다'), [(50.0, '개')])
        self.assertEqual(read('문어만 어 세 마리 잡았습니다'), [(3.0, '마리')])

    def test_quote_cut_from_an_unread_amount_is_rejected(self):
        with self.assertRaisesRegex(ValueError, '뒷부분'):
            assemble('문어 백 스물두 마리 잡았습니다', claim('catches[0].species', '문어', '문어'),
                     claim('catches[0].quantity', 2, '두 마리'))

    def test_bucket_and_foot_are_not_gear_words_for_an_amount(self):
        text = '물 한 통 발에 쏟고 상자 30개 실었습니다'
        self.assertFalse(any('어구 수량' in item for item in assemble(text)['grounding']['coverage_warnings']))
        with self.assertRaisesRegex(ValueError, '어구 문맥'):
            assemble(text, claim('gear_quantity', 30, '상자 30개'))
        self.assertTrue(any('어구 수량' in item for item in assemble(DIALECT_RESET)['grounding']['coverage_warnings']))
        self.assertEqual(fields('통 발로 문어 스무 마리 잡았습니다', claim('gear_type', 'POT', '통 발로'))['gear_type'], 'POT')


if __name__ == '__main__':
    unittest.main()
