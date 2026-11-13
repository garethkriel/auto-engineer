# Task (level 1, topic: String basics)

Implement `ae.StringOps` (static methods):
- `String runLengthEncode(String s)`: "aaabcc" -> "3a1b2c"; "" -> "". Throws `IllegalArgumentException` if `s` contains a digit (ambiguous); `NullPointerException` on null.
- `String runLengthDecode(String s)`: inverse; multi-digit counts allowed ("12a" -> twelve a's). Throws `IllegalArgumentException` for malformed input (missing count, trailing count, count 0, count overflowing int, or total length overflow).
- `boolean isPalindrome(String s)`: ignores case and non-letter/digit characters; empty/no-alnum string is a palindrome.
- `String capitalizeWords(String s)`: upper-cases first letter and lower-cases the rest of each whitespace-separated word, preserving the original whitespace exactly.

Success: `mvn -q -B test` passes. Assumptions: BMP characters only; case via Locale.ROOT.
