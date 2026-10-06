# Copy of morphs/morphemizer.py from the dojo repo (tools/morphs), the PC tool this app replaces: keep them alike.
"""Field text -> morphs with spaCy, following AnkiMorphs' spaCy morphemizer.

A morph is (lemma, inflection), both lowercase; cards are judged by inflection, like the old
AnkiMorphs setup. Differences from AnkiMorphs:
- parse_lowercase (config) chooses what spaCy gets. Lowercased (like AnkiMorphs) is the default: it
  matched the old tags better, because single Core 1000 words and sentence-initial words
  ("Danke!", "Hä?") are then parsed alike. Original case finds names better. Morphs are always lowercase.
- Separable verbs are joined: "Ich rufe dich morgen an" -> lemma "anrufen", inflection "rufe an",
  and the particle "an" is not a morph of its own.
"""
from __future__ import annotations

import html
import re
from collections.abc import Iterable
from dataclasses import dataclass
from functools import cached_property

EXCLUDED_POS = {"X", "SPACE", "SYM", "PUNCT"}  # as AnkiMorphs
VERB_POS = {"VERB", "AUX"}
PUNCTUATION = re.compile(r"^[\W_]+$")  # failsafe for tokens spaCy mis-tags

_TAGS = re.compile(r"<[^>]*>")
_STYLE = re.compile(r"<(style|script)\b.*?</\1>", re.S | re.I)
_CLOZE = re.compile(r"{{c\d+::(.*?)(::[^}]*)?}}", re.S)
_SQUARE = re.compile(r"\[[^]]*]")  # also [sound:..] / [audio:..]
_ROUND = re.compile(r"（[^）]*）|\([^)]*\)")
_DIGITS = re.compile(r"\d")


@dataclass(frozen=True, order=True)
class Morph:
    lemma: str
    inflection: str


@dataclass(frozen=True)
class Token:
    """One spaCy token and what became of it (for `morphs parse-test`)."""
    text: str
    lemma: str
    pos: str
    dep: str
    morph: Morph | None
    skipped: str = ""  # why it is not a morph


class Morphemizer:
    def __init__(self, model: str, *, ignore_names: bool = True, ignore_numbers: bool = True,
                 ignore_bracket_contents: bool = True, join_separable_verbs: bool = True,
                 parse_lowercase: bool = False, names: Iterable[str] = ()):
        self.model = model
        self.ignore_names = ignore_names
        self.ignore_numbers = ignore_numbers
        self.ignore_bracket_contents = ignore_bracket_contents
        self.join_separable_verbs = join_separable_verbs
        self.parse_lowercase = parse_lowercase
        self.names = {n.strip().lower() for n in names if n.strip()}

    @classmethod
    def from_settings(cls, s) -> Morphemizer:
        names = s.names_file.read_text(encoding="utf-8").splitlines() if s.names_file.exists() else ()
        return cls(s.spacy_model, ignore_names=s.ignore_names, ignore_numbers=s.ignore_numbers,
                   ignore_bracket_contents=s.ignore_bracket_contents,
                   join_separable_verbs=s.join_separable_verbs, parse_lowercase=s.parse_lowercase,
                   names=names)

    @cached_property
    def nlp(self):
        import spacy

        return spacy.load(self.model)

    def clean(self, field: str) -> str:
        """Field HTML -> plain text (also used for display)."""
        text = _STYLE.sub("", field)
        text = re.sub(r"<br\s*/?>|<div>", "\n", text, flags=re.I)
        text = _CLOZE.sub(r"\1", text)
        text = html.unescape(_TAGS.sub("", text)).replace("\xa0", " ")
        if self.ignore_bracket_contents:
            text = _ROUND.sub("", _SQUARE.sub("", text))
        if self.ignore_numbers:
            text = _DIGITS.sub("", text)
        return re.sub(r"[ \t]+", " ", text).strip()

    def prepare(self, field: str) -> str:
        """Field HTML -> the text given to spaCy."""
        text = self.clean(field)
        return text.lower() if self.parse_lowercase else text

    def tokens(self, doc) -> list[Token]:
        particles = {}  # verb token index -> its separable particle
        if self.join_separable_verbs:
            for t in doc:
                if t.dep_ == "svp" and t.head.pos_ in VERB_POS and t.head.i != t.i:
                    particles[t.head.i] = t
        joined = {p.i: verb for verb, p in particles.items()}

        out = []
        for t in doc:
            skipped = ""
            if t.pos_ == "X":
                skipped = "not a word to spaCy (X: interjection, foreign word, ...)"
            elif t.pos_ in EXCLUDED_POS or PUNCTUATION.match(t.text):
                skipped = "punctuation"
            elif self.ignore_names and t.pos_ == "PROPN":
                skipped = "name (spaCy)"
            elif self.ignore_names and t.text.lower() in self.names:
                skipped = "name (names.txt)"
            elif self.ignore_numbers and t.pos_ == "NUM":
                skipped = "number"
            elif t.i in joined:
                skipped = f"joined to '{doc[joined[t.i]].text}'"
            morph = None
            if not skipped:
                if t.i in particles:
                    p = particles[t.i]
                    morph = Morph((p.lemma_ + t.lemma_).lower(), f"{t.text} {p.text}".lower())
                else:
                    morph = Morph(t.lemma_.lower(), t.text.lower())
            out.append(Token(t.text, t.lemma_, t.pos_, t.dep_, morph, skipped))
        return out

    def morphs(self, doc) -> list[Morph]:
        return [t.morph for t in self.tokens(doc) if t.morph]

    def parse(self, texts: Iterable[str], batch_size: int = 256) -> Iterable[list[Morph]]:
        """Plain texts (already cleaned) -> morphs of each, in order."""
        for doc in self.nlp.pipe(texts, batch_size=batch_size):
            yield self.morphs(doc)
