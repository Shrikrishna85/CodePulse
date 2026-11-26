USE codepulse;

INSERT INTO app_user (id, username, email, password_hash, role) VALUES
    (1, 'student', 'student@codepulse.local', '$2a$10$c993mwXP3SL.OcPjPHs6yuHPsHPdHU.JURbqvD2PrtJiGVaNWXl6a', 'STUDENT'),
    (2, 'admin', 'admin@codepulse.local', '$2a$10$hHUl4pbxxBHBqEI4uP7mjOBpak6MwmrPgZ3tmnY.zYOSZRZXaTQme', 'ADMIN');

INSERT INTO problem (id, slug, title, statement, difficulty, time_limit_ms, memory_limit_mb, published, created_by)
VALUES
    (1, 'sum-two-numbers', 'Sum Two Numbers', 'Read two integers and print their sum.', 'EASY', 2000, 128, TRUE, 2),
    (2, 'count-vowels', 'Count Vowels', 'Read one lowercase word and print the number of vowels (a, e, i, o, u).', 'EASY', 2000, 128, TRUE, 2),
    (3, 'maximum-in-array', 'Maximum in an Array', 'Read n followed by n integers. Print the largest value.', 'EASY', 2000, 128, TRUE, 2),
    (4, 'palindrome-word', 'Palindrome Word', 'Read one word. Print YES if it reads the same in reverse, otherwise print NO.', 'EASY', 2000, 128, TRUE, 2),
    (5, 'factorial', 'Factorial', 'Read n (0 <= n <= 12). Print n factorial.', 'MEDIUM', 2000, 128, TRUE, 2),
    (6, 'binary-search-index', 'Binary Search Index', 'Read n, target, then n sorted integers. Print the zero-based index of target, or -1 if absent.', 'MEDIUM', 2000, 128, TRUE, 2),
    (7, 'rotate-array-right', 'Rotate an Array Right', 'Read n, k, then n integers. Print the array rotated right by k positions, separated by spaces.', 'MEDIUM', 2000, 128, TRUE, 2);

INSERT INTO problem_tag (name) VALUES
    ('arrays'), ('strings'), ('math'), ('search'), ('sorting')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO problem_tag_link (problem_id, tag_id)
SELECT 1, id FROM problem_tag WHERE name = 'math';
INSERT INTO problem_tag_link (problem_id, tag_id)
SELECT 2, id FROM problem_tag WHERE name = 'strings';
INSERT INTO problem_tag_link (problem_id, tag_id)
SELECT 3, id FROM problem_tag WHERE name = 'arrays';
INSERT INTO problem_tag_link (problem_id, tag_id)
SELECT 4, id FROM problem_tag WHERE name = 'strings';
INSERT INTO problem_tag_link (problem_id, tag_id)
SELECT 5, id FROM problem_tag WHERE name = 'math';
INSERT INTO problem_tag_link (problem_id, tag_id)
SELECT 6, id FROM problem_tag WHERE name = 'search';
INSERT INTO problem_tag_link (problem_id, tag_id)
SELECT 7, id FROM problem_tag WHERE name = 'arrays';
INSERT INTO problem_tag_link (problem_id, tag_id)
SELECT 7, id FROM problem_tag WHERE name = 'sorting';

INSERT INTO test_case (id, problem_id, ordinal, stdin, expected_output, sample) VALUES
    (1, 1, 1, '2 3', '5', TRUE),
    (2, 1, 2, '10 -4', '6', FALSE),
    (3, 2, 1, 'codepulse', '4', TRUE),
    (4, 2, 2, 'rhythm', '0', FALSE),
    (5, 3, 1, '5 3 1 9 -2 4', '9', TRUE),
    (6, 3, 2, '4 7 7 2 5', '7', FALSE),
    (7, 4, 1, 'level', 'YES', TRUE),
    (8, 4, 2, 'world', 'NO', FALSE),
    (9, 5, 1, '5', '120', TRUE),
    (10, 5, 2, '0', '1', FALSE),
    (11, 6, 1, '5 7 1 3 5 7 9', '3', TRUE),
    (12, 6, 2, '4 6 1 3 5 7', '-1', FALSE),
    (13, 7, 1, '5 2 1 2 3 4 5', '4 5 1 2 3', TRUE),
    (14, 7, 2, '4 1 1 2 3 4', '4 1 2 3', FALSE);

INSERT INTO contest (id, slug, title, starts_at, ends_at, created_by)
VALUES (1, 'codepulse-warmup', 'CodePulse Warm-up Contest',
        DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 30 MINUTE),
        DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 90 MINUTE),
        2);

INSERT INTO contest_problem (contest_id, problem_id, ordinal) VALUES
    (1, 1, 1),
    (1, 2, 2),
    (1, 3, 3),
    (1, 4, 4);

INSERT INTO contest_participant (contest_id, user_id) VALUES
    (1, 1),
    (1, 2);
