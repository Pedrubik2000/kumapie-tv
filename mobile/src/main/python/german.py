"""German parsing for the app (called from Kotlin through Chaquopy): Anki fields -> morphs, as morphs does on
the PC with the same spaCy model, so known words come out the same.

A morph's key is its inflection, lowercase ("gehst", "rufe an" for a split verb); known-ness goes by it."""
import json

from morphemizer import Morphemizer

_loaded: dict[str, Morphemizer] = {}


def _morphemizer(model_dir: str) -> Morphemizer:
    m = _loaded.get(model_dir)
    if m is None:
        _loaded.clear()  # one model in memory at a time (the large one takes ~1 GB)
        m = _loaded[model_dir] = Morphemizer(model_dir, parse_lowercase=True)
    return m


def parse_fields(model_dir: str, fields_json: str) -> str:
    """JSON list of field HTML -> JSON list (same order) of [lemma, inflection] lists."""
    m = _morphemizer(model_dir)
    texts = [m.prepare(f) for f in json.loads(fields_json)]
    return json.dumps([[[x.lemma, x.inflection] for x in morphs] for morphs in m.parse(texts)], ensure_ascii=False)


def unload() -> None:
    _loaded.clear()
    import gc
    gc.collect()
