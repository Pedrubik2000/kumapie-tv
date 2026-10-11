"""Show metadata for kumapie (kumapie_plan.md items 20-23): titles, year, poster, description, episode titles.
Used by the phone (Chaquopy) and by the PC's dojo-feed (services/feed/tv.py imports this file), so both match shows
the same way. No keys: anime from AniList -> Jikan (MyAnimeList) -> Kitsu, shows from TVmaze, movies from Wikidata
(TMDB with a key comes later, user 2026-10-11).

    search(kind, query, year=None) -> [{"source", "id", "title", "year", "poster"}], best first
    details(source, id, langs=("en",)) -> {"source", "id", "title": {lang: ...}, "original", "year", "poster",
                                           "description": {lang: ...}, "episodes": {"S-E": title}}

kind: "anime", "shows", "movies" (kumapie's Home categories). Self-test: python meta.py
"""
import html
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request

AGENT = "kumapie (github.com/Pedrubik2000/kumapie-tv)"


def _get(url, data=None):
    headers = {"User-Agent": AGENT, "Accept": "application/vnd.api+json, application/json"}  # Kitsu wants the first
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=json.dumps(data).encode() if data is not None else None, headers=headers)
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < 2:  # Jikan and AniList rate-limit per IP
                time.sleep(2 + 2 * attempt)
                continue
            raise


def _text(s):
    """HTML (AniList, TVmaze, Jikan) to plain text."""
    s = re.sub(r"<br\s*/?>", "\n", s or "")
    s = re.sub(r"\(Source: [^)]*\)|\[Written by [^]]*\]", "", html.unescape(re.sub(r"<[^>]+>", "", s)))
    return re.sub(r"\n{3,}", "\n\n", s).strip()


# ------------------------------------------------------------------ anime: AniList -> Jikan -> Kitsu

_ANILIST_FIELDS = "id idMal title{romaji english native} seasonYear format episodes coverImage{extraLarge} description(asHtml:false)"


def _anilist(query, variables):
    return _get("https://graphql.anilist.co", {"query": query, "variables": variables})["data"]


def _anime_search(q, year):
    try:
        found = _anilist(f"query($s:String){{Page(perPage:10){{media(search:$s,type:ANIME){{{_ANILIST_FIELDS}}}}}}}",
                         {"s": q})["Page"]["media"]
        return [{"source": "anilist", "id": str(m["id"]), "title": m["title"]["english"] or m["title"]["romaji"],
                 "year": m["seasonYear"], "poster": m["coverImage"]["extraLarge"]} for m in found]
    except Exception:
        pass
    try:
        found = _get("https://api.jikan.moe/v4/anime?limit=10&q=" + urllib.parse.quote(q))["data"]
        return [{"source": "mal", "id": str(m["mal_id"]), "title": m["title_english"] or m["title"], "year": m["year"],
                 "poster": m["images"]["jpg"]["large_image_url"]} for m in found]
    except Exception:
        pass
    found = _get("https://kitsu.app/api/edge/anime?page[limit]=10&filter[text]=" + urllib.parse.quote(q))["data"]
    return [{"source": "kitsu", "id": m["id"], "title": m["attributes"]["canonicalTitle"],
             "year": int((m["attributes"]["startDate"] or "0")[:4]) or None,
             "poster": (m["attributes"]["posterImage"] or {}).get("large")} for m in found]


def _mal_episodes(mal_id):
    """Episode titles from Jikan (AniList has none): {"1-N": title}; anime are one season per entry."""
    out, page = {}, 1
    while page and page < 10:
        r = _get(f"https://api.jikan.moe/v4/anime/{mal_id}/episodes?page={page}")
        out.update({f"1-{e['mal_id']}": e["title"] for e in r["data"] if e.get("title")})
        page = page + 1 if r.get("pagination", {}).get("has_next_page") else 0
    return out


def _anime_details(source, id_):
    if source == "anilist":
        m = _anilist(f"query($i:Int){{Media(id:$i,type:ANIME){{{_ANILIST_FIELDS} streamingEpisodes{{title}}}}}}",
                     {"i": int(id_)})["Media"]
        t = m["title"]
        # "Episode 3 - The Title" from the streaming sites: the episode titles when Jikan doesn't answer
        streamed = {f"1-{int(g[0])}": g[1] for e in m.get("streamingEpisodes") or []
                    for g in re.findall(r"^Episode (\d+) - (.+)$", e.get("title") or "")}
        out = {"title": {"en": t["english"] or t["romaji"], "ja": t["native"]}, "original": t["romaji"],
               "year": m["seasonYear"], "poster": m["coverImage"]["extraLarge"],
               "description": {"en": _text(m["description"])}, "mal": m["idMal"], "streamed": streamed}
    elif source == "mal":
        m = _get(f"https://api.jikan.moe/v4/anime/{id_}")["data"]
        out = {"title": {"en": m["title_english"] or m["title"], "ja": m["title_japanese"]}, "original": m["title"],
               "year": m["year"], "poster": m["images"]["jpg"]["large_image_url"],
               "description": {"en": _text(m["synopsis"])}, "mal": m["mal_id"]}
    else:
        a = _get(f"https://kitsu.app/api/edge/anime/{id_}")["data"]["attributes"]
        out = {"title": {"en": a["titles"].get("en") or a["canonicalTitle"], "ja": a["titles"].get("ja_jp")},
               "original": a["titles"].get("en_jp") or a["canonicalTitle"],
               "year": int((a["startDate"] or "0")[:4]) or None, "poster": (a["posterImage"] or {}).get("large"),
               "description": {"en": _text(a["synopsis"])}, "mal": None}
    # AniList's own list first (one request, already made); Jikan (slow, sometimes unreachable) only without it.
    out["episodes"] = out.pop("streamed", None) or {}
    if not out["episodes"] and out["mal"]:
        try:
            out["episodes"] = _mal_episodes(out["mal"])
        except Exception:
            pass
    del out["mal"]
    return out


# ------------------------------------------------------------------ shows: TVmaze (English)

def _show_search(q, year):
    found = _get("https://api.tvmaze.com/search/shows?q=" + urllib.parse.quote(q))
    return [{"source": "tvmaze", "id": str(s["show"]["id"]), "title": s["show"]["name"],
             "year": int((s["show"].get("premiered") or "0")[:4]) or None,
             "poster": (s["show"].get("image") or {}).get("original")} for s in found]


def _show_details(id_):
    s = _get(f"https://api.tvmaze.com/shows/{id_}?embed=episodes")
    return {"title": {"en": s["name"]}, "original": s["name"], "year": int((s.get("premiered") or "0")[:4]) or None,
            "poster": (s.get("image") or {}).get("original"), "description": {"en": _text(s.get("summary"))},
            "episodes": {f"{e['season']}-{e['number']}": e["name"] for e in s["_embedded"]["episodes"] if e.get("number")}}


# ------------------------------------------------------------------ movies: Wikidata (every language)

_WD = "https://www.wikidata.org/w/api.php?format=json&"
FILMS = {"Q11424", "Q202866", "Q24869", "Q506240", "Q20650540", "Q226730"}  # film, animated film, feature, TV film, …


def _claim(e, prop):
    return [c["mainsnak"].get("datavalue", {}).get("value") for c in e.get("claims", {}).get(prop, [])]


def _commons(name):
    return "https://commons.wikimedia.org/wiki/Special:FilePath/" + urllib.parse.quote(name.replace(" ", "_")) + "?width=600"


def _movie_entities(ids, langs):
    r = _get(_WD + "action=wbgetentities&props=labels|descriptions|claims&languages=" + "|".join(langs) + "&ids=" + "|".join(ids))
    return r["entities"]


def _movie_year(e):
    for v in _claim(e, "P577"):
        if v and v.get("time"):
            return int(v["time"][1:5])
    return None


def _movie_poster(e):
    names = [v for v in _claim(e, "P3383") + _claim(e, "P18") if isinstance(v, str)]
    return _commons(names[0]) if names else None


def _movie_search(q, year):
    hits = _get(_WD + "action=wbsearchentities&type=item&limit=15&language=en&search=" + urllib.parse.quote(q))["search"]
    if not hits:
        return []
    ents = _movie_entities([h["id"] for h in hits], ["en"])
    out = []
    for h in hits:
        e = ents.get(h["id"], {})
        if not FILMS & {(v or {}).get("id") for v in _claim(e, "P31")}:
            continue
        out.append({"source": "wikidata", "id": h["id"], "title": e.get("labels", {}).get("en", {}).get("value", h.get("label")),
                    "year": _movie_year(e), "poster": _movie_poster(e)})
    return out


def _movie_details(id_, langs):
    e = _movie_entities([id_], list(dict.fromkeys(["en", *langs])))[id_]
    label = {l: v["value"] for l, v in e.get("labels", {}).items()}
    return {"title": label, "original": label.get("en", ""), "year": _movie_year(e), "poster": _movie_poster(e),
            "description": {l: v["value"] for l, v in e.get("descriptions", {}).items()}, "episodes": {}}


# ------------------------------------------------------------------ public

def search(kind, query, year=None):
    """Candidates for [query] (a show name, a file name's guess), best first; a [year] match moves up."""
    find = {"anime": _anime_search, "shows": _show_search, "movies": _movie_search}[kind]
    found = find(query.strip(), year)
    if year:
        found.sort(key=lambda c: c["year"] != year)  # stable: same order otherwise
    return found


def details(source, id_, langs=("en",)):
    if isinstance(langs, str):  # "en,de" from Kotlin
        langs = langs.split(",")
    if source in ("anilist", "mal", "kitsu"):
        d = _anime_details(source, id_)
    elif source == "tvmaze":
        d = _show_details(id_)
    elif source == "wikidata":
        d = _movie_details(id_, langs)
    else:
        raise ValueError(f"unknown source {source}")
    return {"source": source, "id": str(id_), **d}


if __name__ == "__main__":
    a = search("anime", "Itai no wa Iya nano de Bougyoryoku ni Kyokufuri Shitai to Omoimasu")
    assert a and a[0]["source"] == "anilist" and "BOFURI" in a[0]["title"].upper(), a[:2]
    d = details(a[0]["source"], a[0]["id"])
    assert d["poster"] and d["description"]["en"] and d["title"]["ja"], d
    print("anime:", d["title"], d["year"], len(d["episodes"]), "episode titles;", d["description"]["en"][:60])
    s = search("shows", "Better Call Saul", 2015)
    assert s and s[0]["title"] == "Better Call Saul", s[:2]
    d = details(s[0]["source"], s[0]["id"])
    assert d["episodes"].get("1-1"), d["episodes"]
    print("show:", d["title"], d["year"], d["episodes"]["1-1"], "|", d["description"]["en"][:60])
    m = search("movies", "Mortal Kombat II", 2026)
    assert m and m[0]["year"] == 2026, m[:3]
    d = details(m[0]["source"], m[0]["id"], ("en", "de", "es"))
    print("movie:", d["title"].get("de"), d["year"], d["poster"], "|", d["description"])
    print("ok")
