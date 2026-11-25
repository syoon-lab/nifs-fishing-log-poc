"""Synthetic grounding regressions; no fixture answers enter the extractor."""
import unittest

import extraction


def claim(field, value, quote):
    return {'field': field, 'value': value, 'quote': quote}


def assemble(text, *claims, phase=None):
    return extraction.assemble_claims({'claims': list(claims), 'uncertainties': []}, text, phase)


class GroundedExtraction(unittest.TestCase):
    def test_test_recording_is_not_evidence_of_not_fishing(self):
        text = '시험 녹음입니다. 민어를 잡았습니다.'
        with self.assertRaisesRegex(ValueError, '시험·시연·가상'):
            assemble(text, claim('outcome', 'NOT_FISHED', '시험 녹음입니다'))
        caught = assemble(text, claim('catches[0].species', '민어', '민어를 잡았습니다'))
        self.assertEqual(caught['extraction']['fields']['outcome'], 'CAUGHT')
        self.assertEqual(caught['extraction']['fields']['record_class'], 'UNCLASSIFIED')
        not_fished = assemble('오늘은 조업을 하지 않았습니다.',
                             claim('outcome', 'NOT_FISHED', '조업을 하지 않았습니다'))
        self.assertEqual(not_fished['extraction']['fields']['outcome'], 'NOT_FISHED')

    def test_crew_acting_and_generic_gear_are_known_without_inventing_type(self):
        result = assemble('세 명이 그물을 걷습니다.', claim('workers', 3, '세 명이 그물을 걷습니다'))
        self.assertEqual(result['extraction']['fields']['workers'], 3)
        result = assemble('그물은 열세 폭입니다.', claim('gear_quantity', 13, '열세 폭'))
        self.assertEqual(result['extraction']['fields']['gear_quantity'], 13)
        self.assertIsNone(result['extraction']['fields']['gear_type'])
        with self.assertRaises(ValueError):
            assemble('세 명입니다.', claim('workers', 3, '세 명'))

    def test_short_amount_quote_uses_only_unambiguous_action_sentence(self):
        text = '걷은 자망은 아홉 폭입니다. 다시 놓은 것은 여섯 폭입니다.'
        result = assemble(text, claim('haul_quantity', 9, '아홉 폭'), claim('redeploy_quantity', 6, '여섯 폭'))
        self.assertEqual(result['extraction']['fields']['haul_quantity'], 9)
        self.assertEqual(result['extraction']['fields']['redeploy_quantity'], 6)
        with self.assertRaises(ValueError):
            assemble('통발 아홉 개를 걷고 여섯 개를 다시 놓았습니다.', claim('redeploy_quantity', 9, '아홉 개'))

    def test_exact_korean_numbers_and_decimal(self):
        for raw, expected in [('서른', 30), ('스무', 20), ('스물한', 21), ('쉰 한', 51),
                              ('세', 3), ('오십일', 51), ('천이백삼십사', 1234),
                              ('이점팔', 2.8), ('1,234.5', 1234.5), ('영', 0)]:
            self.assertAlmostEqual(extraction.parse_number(raw), expected, msg=raw)
        for raw in ['신계', '삼채오', '한두', '십십', '삼오', '-1', 'NaN']:
            self.assertIsNone(extraction.parse_number(raw), raw)

    def test_shin_unknown_never_becomes_fifty_or_fraction(self):
        for text, amount in [('통발 신계입니다.', 50), ('물고기 삼채오 킬로그램입니다.', 3.05)]:
            with self.assertRaises(ValueError):
                assemble(text, claim('gear_quantity' if amount == 50 else 'catches[0].weight_kg', amount, text))

    def test_fifty_cannot_become_five(self):
        with self.assertRaises(ValueError):
            assemble('통발 쉰 개입니다.', claim('gear_quantity', 5, '통발 쉰 개'))
        result = assemble('통발 쉰 개입니다.', claim('gear_quantity', 50, '통발 쉰 개'))
        self.assertEqual(result['extraction']['fields']['gear_quantity'], 50)
        self.assertEqual(result['extraction']['fields']['gear_unit'], '개')

    def test_empty_defaults_remain_unknown_but_omissions_flagged(self):
        result = assemble('작업 인원 일곱 명입니다. 통발 여든 개입니다.')
        self.assertIsNone(result['extraction']['fields']['workers'])
        self.assertGreaterEqual(len(result['grounding']['coverage_warnings']), 2)
        self.assertEqual(result['extraction']['fields']['record_class'], 'UNCLASSIFIED')
        self.assertIsNone(result['extraction']['fields']['deployment_date'])

    def test_short_answer_not_arbitrarily_assigned(self):
        for field in ['haul_quantity', 'redeploy_quantity', 'gear_quantity', 'catches[0].quantity']:
            with self.assertRaises(ValueError):
                assemble('서른 개입니다.', claim(field, 30, '서른 개'))
        for text in ['서른 개입니다.', '세 개 다섯 개 두 개', '세 통입니다.']:
            self.assertFalse(assemble(text)['grounding']['coverage_warnings'])
        self.assertTrue(assemble('이름 모를 고기를 세 개 잡았습니다.')['grounding']['coverage_warnings'])

    def test_unrelated_long_conversation_does_not_create_catch_omissions(self):
        text = '학교 물건 세 개를 준비하고 ' + '업무 안내와 일정 이야기를 계속합니다 ' * 20 + '그 잡지는 책상에 있습니다'
        self.assertFalse(assemble(text)['grounding']['coverage_warnings'])
        text = '학교 물건 세 개입니다 ' + '중간 업무 안내 ' * 30 + '나중에 낚시에서 고기를 잡았습니다'
        self.assertFalse(assemble(text)['grounding']['coverage_warnings'])

    def test_explicit_notes_none_derived_from_exact_rule(self):
        result = assemble('작업을 마쳤습니다. 특이사항은 없습니다.')
        self.assertEqual(result['extraction']['fields']['notes_state'], 'EXPLICIT_NONE')
        self.assertTrue(any(x.get('from_rule') == 'explicit_no_notes' for x in result['grounding']['derived']))
        self.assertFalse(result['grounding']['coverage_warnings'])
        for text in ['특이사항이 없으면 종료합니다.', '특이사항은 없습니까?']:
            self.assertEqual(assemble(text)['extraction']['fields']['notes_state'], 'NOT_MENTIONED')

    def test_observation_requires_content_and_derives_reported(self):
        text = '파도가 높아 위치를 옮겼습니다.'
        self.assertTrue(assemble(text)['grounding']['coverage_warnings'])
        result = assemble(text, claim('notes_text', '파도가 높아 위치를 옮겼습니다', '파도가 높아 위치를 옮겼습니다'))
        self.assertEqual(result['extraction']['fields']['notes_state'], 'REPORTED')
        self.assertFalse(result['grounding']['coverage_warnings'])
        with self.assertRaises(ValueError):
            assemble('목표 어종은 민어입니다.', claim('notes_text', '목표 어종은 민어', '목표 어종은 민어'))

    def test_supported_gear_type_derived_from_work_quote(self):
        result = assemble('자망을 걷기 시작합니다.', claim('work_type', '자망을 걷기 시작합니다', '자망을 걷기 시작합니다'))
        self.assertEqual(result['extraction']['fields']['gear_type'], 'GILLNET')
        self.assertIsNone(result['extraction']['fields']['gear_unit'])

    def test_catch_units_and_positive_outcomes_derived(self):
        text = '돌돔 아홉 마리 무게 이점팔 킬로그램입니다.'
        result = assemble(text, claim('catches[0].species', '돌돔', '돌돔 아홉 마리'),
                          claim('catches[0].quantity', 9, '아홉 마리'),
                          claim('catches[0].weight_kg', 2.8, '이점팔 킬로그램'))
        fields = result['extraction']['fields']
        self.assertEqual(fields['catches'][0]['unit'], '마리')
        self.assertEqual(fields['catches'][0]['outcome'], 'CAUGHT')
        self.assertEqual(fields['outcome'], 'CAUGHT')
        self.assertTrue(any(e['field'] == 'catches[0].unit' for e in result['extraction']['evidence']))

    def test_grams_convert_only_to_stated_weight(self):
        result = assemble('참돔 오백 그램을 잡았습니다.', claim('catches[0].species', '참돔', '참돔 오백 그램'),
                          claim('catches[0].weight_kg', .5, '참돔 오백 그램'))
        self.assertEqual(result['extraction']['fields']['catches'][0]['weight_kg'], .5)
        with self.assertRaises(ValueError):
            assemble('참돔 두 상자를 잡았습니다.', claim('catches[0].weight_kg', 2, '참돔 두 상자'))

    def test_species_only_row_does_not_hide_omitted_numbers(self):
        result = assemble('갈치 세 마리 무게 팔 킬로그램입니다.',
                          claim('catches[0].species', '갈치', '갈치'))
        warnings = result['grounding']['coverage_warnings']
        self.assertTrue(any('수량' in item for item in warnings))
        self.assertTrue(any('중량' in item for item in warnings))
        text = '갈치 팔 킬로그램입니다. 민어 십이 킬로그램입니다.'
        result = assemble(text, claim('catches[0].species', '갈치', '갈치'),
                          claim('catches[0].weight_kg', 8, '갈치 팔 킬로그램'),
                          claim('catches[1].species', '민어', '민어'))
        self.assertTrue(any('십이 킬로그램' in item for item in result['grounding']['coverage_warnings']))

    def test_redundant_kg_quantity_only_removed_with_verified_matching_weight(self):
        text = '농어 구 킬로그램입니다.'
        claims = [claim('catches[0].species', '농어', '농어'),
                  claim('catches[0].quantity', 9, '구 킬로그램'),
                  claim('catches[0].unit', '킬로그램', '구 킬로그램'),
                  claim('catches[0].weight_kg', 9, '구 킬로그램')]
        result = assemble(text, *claims)
        row = result['extraction']['fields']['catches'][0]
        self.assertEqual(row['weight_kg'], 9)
        self.assertIsNone(row['quantity'])
        self.assertIsNone(row['unit'])
        self.assertEqual(len(result['grounding']['normalizations'][0]['removed_claims']), 2)
        self.assertEqual(claims[1]['value'], 9)
        with self.assertRaises(ValueError):
            assemble(text, *claims[:-1])
        wrong = claims[:-1] + [claim('catches[0].weight_kg', 8, '구 킬로그램')]
        with self.assertRaises(ValueError):
            assemble(text, *wrong)

    def test_haul_and_redeploy_require_own_action_quote(self):
        text = '통발 서른 개를 양망하고 통발 스물다섯 개를 다시 투망했습니다.'
        result = assemble(text, claim('haul_quantity', 30, '통발 서른 개를 양망'),
                          claim('redeploy_quantity', 25, '통발 스물다섯 개를 다시 투망'))
        self.assertEqual(result['extraction']['fields']['haul_quantity'], 30)
        for field, quote in [('redeploy_quantity', '서른 개'), ('redeploy_quantity', '통발 서른 개를 양망'),
                             ('haul_quantity', '통발 스물다섯 개를 다시 투망')]:
            with self.assertRaises(ValueError):
                assemble(text, claim(field, 30 if '서른' in quote else 25, quote))

    def test_recovery_is_not_redeployment_and_future_is_not_done(self):
        for text in ['통발을 회수했습니다.', '통발을 양망했습니다.', '내일 다시 투망할 계획입니다.']:
            with self.assertRaises(ValueError):
                assemble(text, claim('disposition', 'REDEPLOYED', text))
        result = assemble('통발을 다시 투망했습니다.', claim('disposition', 'REDEPLOYED', '다시 투망했습니다'))
        self.assertEqual(result['extraction']['fields']['disposition'], 'REDEPLOYED')

    def test_species_values_must_match_and_target_is_not_catch(self):
        with self.assertRaises(ValueError):
            assemble('가재 세 마리', claim('catches[0].species', '꽃게', '가재 세 마리'))
        with self.assertRaises(ValueError):
            assemble('목표 어종은 민어입니다.', claim('catches[0].species', '민어', '민어'))

    def test_target_species_requires_specific_target_relation(self):
        for text in ['꽃게를 잡았습니다.', '목표 어종은 민어지만 대신 꽃게를 잡았습니다.']:
            result = assemble(text, claim('target_species', '꽃게', '꽃게'))
            self.assertIsNone(result['extraction']['fields']['target_species'])
            self.assertEqual(result['grounding']['rejected_claims'][0]['claim'], claim('target_species', '꽃게', '꽃게'))
            self.assertTrue(result['extraction']['uncertainties'])
        for text in ['목표 어종은 민어입니다.', '민어를 목표로 조업합니다.', '대상 어종은 민어입니다.']:
            self.assertEqual(assemble(text, claim('target_species', '민어', '민어'))['extraction']['fields']['target_species'], '민어')

    def test_uncertain_target_retains_other_supported_facts_and_rejected_claim(self):
        text = '통발을 걷기 시작합니다. 목표 정보는 불분명하고 민어가 보입니다. 작업 인원 네 명입니다.'
        original = claim('target_species', '민어', '민어')
        result = assemble(text, original, claim('workers', 4, '작업 인원 네 명'))
        fields = result['extraction']['fields']
        self.assertIsNone(fields['target_species'])
        self.assertEqual(fields['workers'], 4)
        self.assertEqual(fields['work_type'], '통발을 걷기 시작합니다')
        self.assertEqual(result['grounding']['rejected_claims'][0]['claim'], original)
        with self.assertRaises(ValueError):
            assemble(text, claim('target_species', '꽃게', '민어'))
        with self.assertRaises(ValueError):
            assemble(text, original, claim('workers', 5, '작업 인원 네 명'))

    def test_recording_introduction_is_excluded_without_losing_observation(self):
        text = '시험 기록입니다. 바람이 강해 위치를 옮겼습니다.'
        intro = claim('work_type', '시험 기록입니다', '시험 기록입니다')
        notes = '바람이 강해 위치를 옮겼습니다'
        result = assemble(text, intro, claim('notes_text', notes, notes))
        self.assertIsNone(result['extraction']['fields']['work_type'])
        self.assertEqual(result['extraction']['fields']['notes_text'], notes)
        self.assertEqual(result['extraction']['fields']['notes_state'], 'REPORTED')
        self.assertEqual(result['grounding']['rejected_claims'][0]['claim'], intro)
        self.assertTrue(any('제외' in item for item in result['extraction']['uncertainties']))
        self.assertFalse(result['grounding']['coverage_warnings'])

    def test_introduction_exclusion_retains_or_recovers_real_gear_action(self):
        text = '시험 녹음입니다. 통발을 양망했습니다.'
        result = assemble(text, claim('work_type', '시험 녹음입니다', '시험 녹음입니다'))
        self.assertEqual(result['extraction']['fields']['work_type'], '통발을 양망했습니다')
        self.assertEqual(result['extraction']['fields']['gear_type'], 'POT')
        self.assertEqual(len(result['grounding']['rejected_claims']), 1)
        result = assemble(text, claim('work_type', text, text))
        self.assertEqual(result['extraction']['fields']['work_type'], text)
        self.assertFalse(result['grounding']['rejected_claims'])

    def test_only_nonwork_value_is_excluded_not_real_start_end_or_movement(self):
        for text in ('녹음을 시작합니다.', '기록을 종료했습니다.', '안녕하세요.', '광고입니다.'):
            with self.subTest(text=text):
                result = assemble(text, claim('work_type', text, text))
                self.assertIsNone(result['extraction']['fields']['work_type'])
                self.assertEqual(len(result['grounding']['rejected_claims']), 1)
        for text in ('작업을 시작합니다.', '조업을 종료했습니다.', '위치를 옮겼습니다.',
                     '시연용 통발을 양망했습니다.'):
            with self.subTest(text=text):
                result = assemble(text, claim('work_type', text, text))
                self.assertEqual(result['extraction']['fields']['work_type'], text)
                self.assertFalse(result['grounding']['rejected_claims'])

    def test_narrow_caught_quote_expands_to_exact_species_fact(self):
        text = '통발을 회수하였습니다. 꽃게를 잡았습니다.'
        result = assemble(text, claim('catches[0].species', '꽃게', '꽃게를 잡았습니다'),
                          claim('catches[0].outcome', 'CAUGHT', '잡았습니다'))
        fields = result['extraction']['fields']
        self.assertEqual(fields['outcome'], 'CAUGHT')
        self.assertIsNone(fields['catches'][0]['quantity'])
        quotes = {e['field']: e['quote'] for e in result['extraction']['evidence']}
        self.assertEqual(quotes['catches[0].outcome'], '꽃게를 잡았습니다')
        self.assertEqual(quotes['outcome'], '꽃게를 잡았습니다')
        self.assertEqual(result['grounding']['accepted_claims'][1]['quote'], '잡았습니다')

    def test_missing_caught_outcome_is_derived_from_explicit_species_sentence(self):
        result = assemble('갈치를 잡았습니다.', claim('catches[0].species', '갈치', '갈치'))
        self.assertEqual(result['extraction']['fields']['catches'][0]['outcome'], 'CAUGHT')
        self.assertEqual(result['extraction']['fields']['outcome'], 'CAUGHT')
        self.assertIsNone(result['extraction']['fields']['catches'][0]['quantity'])
        self.assertIsNone(result['extraction']['fields']['catches'][0]['weight_kg'])
        self.assertTrue(any(d.get('from_rule') == 'explicit_species_catch' for d in result['grounding']['derived']))

    def test_caught_context_does_not_cross_species_negation_or_hypothesis(self):
        cases = [
            ('갈치는 보았고 꽃게를 잡았습니다.', '갈치'),
            ('갈치를 못 잡았습니다. 꽃게를 잡았습니다.', '갈치'),
            ('갈치는 못 잡았지만 꽃게를 잡았습니다.', '갈치'),
            ('갈치를 잡았을 예정입니다.', '갈치'),
            ('갈치를 잡았습니다라는 말은 거짓입니다.', '갈치'),
            ('참꽃게를 잡았습니다.', '꽃게'),
        ]
        for text, species in cases:
            with self.subTest(text=text):
                result = assemble(text, claim('catches[0].species', species, species))
                self.assertEqual(result['extraction']['fields']['catches'][0]['outcome'], 'UNCONFIRMED')
                with self.assertRaises(ValueError):
                    assemble(text, claim('catches[0].species', species, species),
                             claim('catches[0].outcome', 'CAUGHT', '잡았습니다' if '잡았습니다' in text else text))
        text = '갈치는 못 잡았습니다. 꽃게를 잡았습니다.'
        result = assemble(text, claim('catches[0].species', '갈치', '갈치'),
                          claim('catches[0].outcome', 'NO_CATCH', '갈치는 못 잡았습니다'),
                          claim('catches[1].species', '꽃게', '꽃게'))
        self.assertEqual([row['outcome'] for row in result['extraction']['fields']['catches']], ['NO_CATCH', 'CAUGHT'])

    def test_explicit_gear_action_derived_verbatim_but_unknown_action_not_repaired(self):
        result = assemble('통발 회수를 마쳤습니다. 특이사항은 없습니다.')
        self.assertEqual(result['extraction']['fields']['work_type'], '통발 회수를 마쳤습니다')
        self.assertEqual(result['extraction']['fields']['gear_type'], 'POT')
        for text in ['통발 해수를 마쳤습니다.', '통발을 회수할 예정입니다.',
                     '통발을 회수했습니다라는 말은 사실이 아닙니다.', '통발을 회수했습니까?']:
            with self.subTest(text=text):
                self.assertIsNone(assemble(text)['extraction']['fields']['work_type'])
        damaged = assemble('통발 해수를 마쳤습니다.', claim('work_type', '통발 해수를 마쳤습니다', '통발 해수를 마쳤습니다'))
        self.assertTrue(any('전사 표현' in x for x in damaged['extraction']['uncertainties']))

    def test_unspoken_or_unreadable_gear_amount_is_marked_unknown_without_guess(self):
        for text in ['통발 신계 작업 인원 두 명입니다.', '그물을 걷었습니다.']:
            result = assemble(text)
            self.assertTrue(any('어구 수량' in item for item in result['extraction']['uncertainties']))
            self.assertTrue(all(result['extraction']['fields'][k] is None for k in ('gear_quantity', 'haul_quantity', 'redeploy_quantity')))

    def test_missing_no_catch_species_row_is_flagged_without_inventing_zero(self):
        text = '방어는 한 마리도 없고 쥐치 네 마리를 잡았습니다.'
        result = assemble(text, claim('catches[0].species', '쥐치', '쥐치'),
                          claim('catches[0].quantity', 4, '네 마리'))
        self.assertTrue(any('방어' in item for item in result['grounding']['coverage_warnings']))
        self.assertEqual(len(result['extraction']['fields']['catches']), 1)

    def answer_context(self, target='haul_quantity', unit='개'):
        from answer_context import QUESTIONS
        _, question = next(value for (field, _), value in QUESTIONS.items()
                           if field == target and value[0] == unit)
        return {'version': 1, 'source_id': 'source', 'work_id': 'op:operation',
                'recording_id': 'answer', 'session_id': 'session', 'operation_id': 'operation',
                'event_id': 'event', 'target_field': target, 'expected_unit': unit, 'question': question}

    def test_linked_question_routes_only_exact_spoken_answer_without_inventing_gear_type(self):
        for field, unit, answer, number in [('haul_quantity', '개', '서른 개입니다.', 30),
                                           ('redeploy_quantity', '폭', '네, 다섯 폭입니다.', 5)]:
            context = self.answer_context(field, unit)
            result = extraction.assemble_claims({'claims': [], 'uncertainties': []}, answer,
                                                phase='WORK', question_context=context)
            fields = result['extraction']['fields']
            self.assertEqual(fields[field], number)
            self.assertEqual(fields['gear_unit'], unit)
            self.assertIsNone(fields['gear_type'])
            self.assertIsNone(fields['gear_quantity'])
            self.assertEqual(result['grounding']['question_context'], context)
            self.assertTrue(all(e['quote'] in answer for e in result['extraction']['evidence']))
            result = extraction.assemble_claims({'claims': [claim(field, number, answer)], 'uncertainties': []},
                                                answer, phase='WORK', question_context=context)
            self.assertEqual(result['extraction']['fields'][field], number)

    def test_question_metadata_cannot_supply_missing_numbers_or_override_answer(self):
        context = self.answer_context()
        for text in ['모릅니다.', '서른입니다.', '열에서 열두 개입니다.', '열 개, 아니 스무 개입니다.',
                     '세 폭입니다.', '서른 개는 어획 수량입니다.', '통발 서른 개를 다시 놓았습니다.']:
            result = extraction.assemble_claims({'claims': [], 'uncertainties': []}, text,
                                                phase='WORK', question_context=context)
            self.assertIsNone(result['extraction']['fields']['haul_quantity'], text)
        for field in ('redeploy_quantity', 'gear_quantity', 'catches[0].quantity'):
            with self.subTest(field=field), self.assertRaises(ValueError):
                extraction.assemble_claims({'claims': [claim(field, 30, '서른 개')], 'uncertainties': []},
                                            '서른 개입니다.', phase='WORK', question_context=context)
        with self.assertRaises(ValueError):
            extraction.assemble_claims({'claims': [claim('haul_quantity', 30, '서른 개')], 'uncertainties': []},
                                        '통발 서른 개를 다시 놓았습니다.', phase='WORK', question_context=context)

    def test_invalid_or_other_phase_question_context_is_rejected(self):
        context = self.answer_context()
        for phase in (None, 'START', 'END', 'LEGACY'):
            with self.subTest(phase=phase), self.assertRaises(ValueError):
                extraction.assemble_claims({'claims': [], 'uncertainties': []}, '서른 개입니다.',
                                            phase=phase, question_context=context)
        with self.assertRaises(ValueError):
            extraction.assemble_claims({'claims': [], 'uncertainties': []}, '서른 개입니다.',
                                        phase='WORK', question_context=context | {'value': 999})

    def test_same_unit_counts_cannot_be_swapped_between_species(self):
        text = '조기 네 마리를 잡았습니다. 민어 여섯 마리를 잡았습니다.'
        with self.assertRaises(ValueError):
            assemble(text, claim('catches[0].species', '조기', '조기'),
                     claim('catches[1].species', '민어', '민어'),
                     claim('catches[0].quantity', 6, '민어 여섯 마리'))

    def test_total_weight_not_allocated_across_species(self):
        text = '조기 네 마리와 민어 여섯 마리, 총 삼 킬로그램입니다.'
        with self.assertRaises(ValueError):
            assemble(text, claim('catches[0].species', '조기', '조기'),
                     claim('catches[1].species', '민어', '민어'),
                     claim('catches[1].weight_kg', 3, '총 삼 킬로그램'))

    def test_true_repeated_catches_retained_but_one_mention_not_copied(self):
        def rows():
            return [claim(f'catches[{i}].{field}', value, quote) for i in range(2)
                    for field, value, quote in [('species', '숭어', '숭어 두 마리'), ('quantity', 2, '두 마리')]]
        text = '숭어 두 마리를 잡았습니다. 또 숭어 두 마리를 잡았습니다.'
        self.assertEqual(len(assemble(text, *rows())['extraction']['fields']['catches']), 2)
        with self.assertRaises(ValueError):
            assemble('숭어 두 마리를 잡았습니다.', *rows())

    def test_no_catch_scope_and_null_amounts(self):
        result = assemble('민어는 한 마리도 못 잡았습니다.',
                          claim('catches[0].species', '민어', '민어'),
                          claim('catches[0].outcome', 'NO_CATCH', '민어는 한 마리도 못 잡았습니다'))
        self.assertIsNone(result['extraction']['fields']['catches'][0]['quantity'])
        self.assertEqual(result['extraction']['fields']['outcome'], 'UNCONFIRMED')
        with self.assertRaises(ValueError):
            assemble('민어는 없습니다.', claim('outcome', 'NO_CATCH', '민어는 없습니다'))

    def test_species_no_catch_cannot_be_promoted_even_when_species_claim_omitted(self):
        for text, quote in [('민어는 한 마리도 못 잡았습니다.', '민어는 한 마리도 못 잡았습니다'),
                             ('민어는 한 마리도 못 잡았습니다.', '한 마리도 못 잡았습니다'),
                             ('민어의 어획은 없습니다.', '어획은 없습니다'),
                             ('한 마리도 못 잡았습니다.', '한 마리도 못 잡았습니다')]:
            with self.assertRaises(ValueError):
                assemble(text, claim('outcome', 'NO_CATCH', quote))
        for text in ['아무것도 못 잡았습니다.', '모든 어종을 못 잡았습니다.',
                     '어떤 어종도 잡지 못했습니다.', '전체 어획은 없습니다.', '어획은 없습니다.']:
            self.assertEqual(assemble(text, claim('outcome', 'NO_CATCH', text))['extraction']['fields']['outcome'], 'NO_CATCH')

    def test_explicit_catch_without_amount_keeps_fact_and_null_numbers(self):
        text = '갈치를 잡았습니다.'
        result = assemble(text, claim('catches[0].species', '갈치', '갈치'),
                          claim('catches[0].outcome', 'CAUGHT', '갈치를 잡았습니다'))
        fields = result['extraction']['fields']
        self.assertEqual(fields['outcome'], 'CAUGHT')
        self.assertEqual(fields['catches'][0]['outcome'], 'CAUGHT')
        self.assertIsNone(fields['catches'][0]['quantity'])
        self.assertIsNone(fields['catches'][0]['weight_kg'])
        self.assertTrue(any(x['field'] == 'outcome' and x['from_field'] == 'catches[0].outcome'
                            for x in result['grounding']['derived']))
        for text in ['갈치를 못 잡았습니다.', '갈치를 잡을 예정입니다.',
                     '예를 들어 갈치를 잡았습니다.', '갈치를 잡았습니까?']:
            with self.assertRaises(ValueError):
                assemble(text, claim('catches[0].species', '갈치', '갈치'),
                         claim('catches[0].outcome', 'CAUGHT', text))

    def test_subset_quote_cannot_hide_korean_range_or_correction(self):
        for text, value, quote in [('갈치를 열에서 열두 마리 잡았습니다.', 12, '열두 마리'),
                                   ('갈치를 열마리에서 열두마리 잡았습니다.', 12, '열두마리'),
                                   ('갈치를 10~12마리 잡았습니다.', 12, '12마리'),
                                   ('갈치는 세 마리, 아니 네 마리를 잡았습니다.', 4, '네 마리')]:
            with self.assertRaises(ValueError):
                assemble(text, claim('catches[0].species', '갈치', '갈치'),
                         claim('catches[0].quantity', value, quote))
            unknown = extraction.assemble_claims({'claims': [], 'uncertainties': ['수량 범위·정정 확인 필요']}, text)
            self.assertFalse(unknown['grounding']['coverage_warnings'])

    def test_schema_forbidden_fields_types_and_conflicting_claims(self):
        for field, value in [('deployment_date', '2026-01-01'), ('record_class', 'FIELD'),
                             ('unknown', 3), ('workers', True), ('catches[0].quantity', float('nan')),
                             ('catches[0].approximate', 1), ('gear_type', {})]:
            with self.assertRaises(ValueError):
                assemble('작업 인원 세 명', claim(field, value, '작업 인원 세 명'))
        with self.assertRaises(ValueError):
            assemble('작업 인원 세 명', claim('workers', 3, '작업 인원 세 명'), claim('workers', 4, '작업 인원 세 명'))
        with self.assertRaises(ValueError):
            assemble('숭어', claim('catches[1].species', '숭어', '숭어'))

    def test_questions_hypothetical_and_dates_are_not_counts(self):
        for text in ['민어 세 마리인가요?', '예를 들어 민어 세 마리입니다.']:
            with self.assertRaises(ValueError):
                assemble(text, claim('catches[0].species', '민어', '민어'), claim('catches[0].quantity', 3, '세 마리'))
        with self.assertRaises(ValueError):
            assemble('통발은 3일에 투망했습니다.', claim('gear_quantity', 3, '통발은 3일에 투망했습니다'))


if __name__ == '__main__':
    unittest.main()
