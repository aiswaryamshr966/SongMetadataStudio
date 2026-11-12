import json
import sys
import traceback


def names(items):
    result = []
    for item in items or []:
        value = item.get("name") if isinstance(item, dict) else item
        if value and str(value).strip() and str(value).strip() not in result:
            result.append(str(value).strip())
    return result


def best_thumbnail(items):
    candidates = [item for item in (items or []) if item.get("url")]
    if not candidates:
        return ""
    chosen = max(candidates, key=lambda item: int(item.get("width", 0)) * int(item.get("height", 0)))
    return chosen.get("url", "")


def search(yt, query, limit):
    results = yt.search(query, filter="songs")
    rows = []
    for item in results[:limit]:
        video_id = item.get("videoId")
        if not video_id:
            continue
        album = item.get("album") or {}
        rows.append({
            "videoId": video_id,
            "title": item.get("title", ""),
            "artists": names(item.get("artists")),
            "album": album.get("name", ""),
            "albumId": album.get("id", ""),
            "duration": item.get("duration", ""),
            "durationSeconds": item.get("duration_seconds"),
            "year": item.get("year"),
            "trackNumber": item.get("trackNumber"),
            "thumbnailUrl": best_thumbnail(item.get("thumbnails")),
        })
    return {"results": rows}


def details(yt, video_id, album_id):
    song = yt.get_song(video_id)
    video_details = song.get("videoDetails") or {}
    album = yt.get_album(album_id) if album_id else {}
    tracks = album.get("tracks") or []
    track = next((item for item in tracks if item.get("videoId") == video_id), {})

    credits = {}
    credit_id = track.get("creditsBrowseId")
    warnings = []
    if credit_id:
        credits = yt.get_song_credits(credit_id)
    else:
        warnings.append("YouTube Music did not provide detailed song credits.")

    performed = names((credits.get("performed_by") or {}).get("data"))
    written = names((credits.get("written_by") or {}).get("data"))
    album_artists = names(album.get("artists"))
    track_artists = names(track.get("artists"))
    if not performed:
        performed = track_artists
    if not album_artists and track_artists:
        album_artists = [track_artists[0]]

    artwork = best_thumbnail(album.get("thumbnails"))
    if not artwork:
        artwork = best_thumbnail((video_details.get("thumbnail") or {}).get("thumbnails"))
    if not artwork:
        warnings.append("No album artwork was returned by YouTube Music.")

    year = album.get("year") or track.get("year")
    if year and not str(year).isdigit():
        year = None
    track_total = album.get("trackCount") or (len(tracks) if tracks else None)
    return {
        "title": track.get("title") or video_details.get("title", ""),
        "artist": ", ".join(performed),
        "album": album.get("title") or track.get("album", ""),
        "albumArtist": album_artists[0] if album_artists else "",
        "composer": "",
        "writer": ", ".join(written),
        "publisher": "",
        "genre": "",
        "date": str(year) if year else "",
        "trackNumber": track.get("trackNumber"),
        "trackTotal": track_total,
        "discNumber": None,
        "discTotal": None,
        "sourceUrl": "https://music.youtube.com/watch?v=" + video_id,
        "coverUrl": artwork,
        "creditedArtists": performed,
        "warnings": warnings,
    }


def main():
    if len(sys.argv) < 2:
        raise ValueError("Missing helper operation.")

    try:
        from ytmusicapi import YTMusic
    except ImportError as error:
        print(json.dumps({"error": "ytmusicapi is missing. Run: python -m pip install -r requirements.txt"}))
        raise SystemExit(2) from error

    operation = sys.argv[1]
    yt = YTMusic()
    if operation == "health":
        print(json.dumps({"available": True}))
        return
    if operation == "search":
        query = sys.argv[2]
        limit = max(1, min(int(sys.argv[3]), 25))
        print(json.dumps(search(yt, query, limit), ensure_ascii=False))
        return
    if operation == "details":
        video_id = sys.argv[2]
        album_id = sys.argv[3] if len(sys.argv) > 3 else ""
        print(json.dumps(details(yt, video_id, album_id), ensure_ascii=False))
        return
    raise ValueError("Unknown helper operation: " + operation)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as error:
        print(json.dumps({"error": str(error)}))
        traceback.print_exc(file=sys.stderr)
        raise SystemExit(1)
