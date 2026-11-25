import unittest

import evaluate_extraction as evaluator


class EvaluationMatching(unittest.TestCase):
    def evaluate_catches(self, expected, actual):
        cases = [{'id': 'test', 'expected': {'catches': expected}}]
        outputs = {'test': {'id': 'test', 'result': {'extraction': {
            'status': 'DRAFT', 'fields': {'catches': actual}, 'uncertainties': []}}}}
        return evaluator.evaluate(cases, outputs)

    def test_omitted_species_does_not_steal_another_species_quantity(self):
        result = self.evaluate_catches(
            [{'species': '방어', 'quantity': 0}, {'species': '쥐치', 'quantity': 4}],
            [{'species': '쥐치', 'quantity': 4}])
        checks = {r['field']: r for r in result['cases'][0]['checks']}
        self.assertTrue(checks['catches[0].species']['missing'])
        self.assertTrue(checks['catches[0].quantity']['missing'])
        self.assertIsNone(checks['catches[0].quantity']['actual_field'])
        self.assertTrue(checks['catches[1].species']['passed'])
        self.assertTrue(checks['catches[1].quantity']['passed'])
        self.assertFalse(result['cases'][0]['passed'])
        self.assertEqual(result['metrics']['false_numeric_claims_on_labeled_fields'], 0)
        self.assertEqual(result['metrics']['accepted_false_numeric_claims'], 0)

    def test_wrong_value_for_same_species_remains_false_numeric_claim(self):
        result = self.evaluate_catches([{'species': '쥐치', 'quantity': 4}],
                                       [{'species': '쥐치', 'quantity': 40}])
        self.assertEqual(result['metrics']['accepted_false_numeric_claims'], 1)

    def test_reordering_and_repeated_rows_do_not_lose_multiplicity(self):
        expected = [{'species': '쥐치', 'quantity': 4}, {'species': '방어', 'quantity': 0},
                    {'species': '쥐치', 'quantity': 4}]
        actual = [expected[1], expected[2], expected[0]]
        self.assertTrue(self.evaluate_catches(expected, actual)['cases'][0]['passed'])
        dropped = self.evaluate_catches(expected, actual[:-1])
        self.assertFalse(dropped['cases'][0]['passed'])
        self.assertEqual(dropped['metrics']['accepted_false_numeric_claims'], 0)


if __name__ == '__main__':
    unittest.main()
