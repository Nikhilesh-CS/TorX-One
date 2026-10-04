import unittest
from check_logging_privacy import findings, log_calls, scan, DIAGNOSTICS


class LoggingPrivacyTest(unittest.TestCase):
    TAG = 'private const val TAG = "Fixture"\n'

    def test_current_application_obeys_logging_policy(self):
        self.assertEqual([], scan())

    def test_message_content_identity_filename_onion_and_exception_injections_fail(self):
        unsafe = [
            'Log.d(TAG, "message=$plaintext")',
            'Log.i(TAG, "peer=${envelope.senderIdentity.take(8)}")',
            'android.util.Log.w(TAG, "file=${file.name}")',
            'Log.e(TAG, onionAddress)',
            'Log.e(TAG, "Connection failed", exception)',
            'Log.d(TAG, "SDP: " + sdp)',
            'Log.i(peerIdentity, "Static stage")',
            'Log.i(TAG, """body=$body""")',
            'println(secret)',
            'error.printStackTrace()',
            'import android.util.Log as L\nL.d(TAG, plaintext)',
        ]
        for source in unsafe:
            with self.subTest(source=source):
                self.assertTrue(findings(self.TAG + source))

    def test_parser_does_not_miss_nested_templates_multiline_calls_or_throwables(self):
        source = '''Log.e(
            TAG,
            "Failed: ${if (secret.isBlank()) "empty" else secret}",
            error
        )'''
        calls = log_calls(source)
        self.assertEqual(1, len(calls))
        self.assertEqual(3, len(calls[0].arguments))
        self.assertEqual(1, len(findings(self.TAG + source)))

    def test_static_stages_and_comments_are_safe(self):
        self.assertEqual([], findings(self.TAG + 'Log.w(TAG, "Authenticated frame rejected")'))
        self.assertEqual([], findings('// Log.e(TAG, secret)\n/* Log.d(TAG, payload) */'))

    def test_dynamic_diagnostics_exception_is_exact_and_cannot_authorize_other_logs(self):
        allowed = 'Log.i("TORX_DIAG", line(name, relationship, conversation, delivery, sequence, transport, state, attempt, elapsedMs, present))'
        self.assertEqual([], findings(allowed, DIAGNOSTICS))
        self.assertTrue(findings(allowed, "NewLogger.kt"))
        self.assertTrue(findings('Log.i("TORX_DIAG", payload)', DIAGNOSTICS))
        self.assertTrue(findings('Log.i("TORX_DIAG", line(name, relationship, conversation, delivery, sequence, transport, state, attempt, elapsedMs, present) + payload)', DIAGNOSTICS))


if __name__ == "__main__":
    unittest.main()
