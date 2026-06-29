CREATE TABLE tricky (
    id   VARCHAR(50) PRIMARY KEY,
    note VARCHAR(200)
);

-- The value below contains a semicolon and a double-dash that must NOT be
-- treated as a statement terminator or a comment, because they sit inside a
-- single-quoted string literal.
INSERT INTO tricky (id, note) VALUES ('a', 'has; a semicolon -- and dashes');
