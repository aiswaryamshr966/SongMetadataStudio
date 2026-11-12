# Song Metadata Studio

A local Spring Boot app for finding a matching YouTube Music release, reviewing its metadata, and creating an Explorer-friendly FLAC copy with embedded artwork. The original audio file is not modified.

## Requirements

- Java 21
- Maven 3.9+
- FFmpeg and FFprobe on `PATH`
- Python 3.9+ with `ytmusicapi`

Install the Python dependency once:

```powershell
python -m pip install -r requirements.txt
```

## Run

From the project directory:

```powershell
mvn spring-boot:run
```

Open [http://127.0.0.1:8765](http://127.0.0.1:8765). The app only binds to localhost.

1. Paste the full path to a local audio file.
2. Compare the YouTube Music results with the source duration and choose the correct version.
3. Review or edit the suggested tags.
4. Create the FLAC. It is saved beside the source using the same filename and `.flac` extension.

If that FLAC already exists, the app leaves it alone unless **Replace an existing FLAC** is checked. Cover art is fetched from the selected YouTube Music result and embedded in the FLAC. Song audio is read only from the local source file; the app does not download audio from YouTube Music.

You can change executable paths with `PYTHON_EXECUTABLE`, `FFMPEG_EXECUTABLE`, and `FFPROBE_EXECUTABLE` environment variables.

## Security and privacy

This is a local desktop app, not a hosted service. It has no authentication and processes local files at the paths you provide. Keep it bound to `127.0.0.1`; do not expose it to your network or the internet. Search terms and selected track IDs are sent to YouTube Music, and cover art is fetched from YouTube Music image hosts. Audio files remain on your computer.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
