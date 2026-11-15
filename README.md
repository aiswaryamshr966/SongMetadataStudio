<div align="center">

# 🎧 Song Metadata Studio

### A calmer, more personal way to finish your music library.

Find the right release, review the details, and create a verified FLAC copy — all from a private workbench on your own Windows PC.

<br>

![Java 21](https://img.shields.io/badge/Java-21-3A6B55?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-547D3C?style=for-the-badge&logo=springboot&logoColor=white)
![Windows](https://img.shields.io/badge/Windows-Desktop-BC5B25?style=for-the-badge&logo=windows&logoColor=white)
![Local first](https://img.shields.io/badge/Local--first-Your_files_stay_yours-262820?style=for-the-badge)

</div>

<br>

> **Your originals stay untouched.** Song Metadata Studio works with a temporary local copy, helps you review candidate metadata, and writes a new FLAC to a folder you can find again.

## The short version

| Bring in | Review | Make it yours |
|:--|:--|:--|
| Choose one track, or build a batch of up to **100**. | Compare release suggestions and edit the tags. Missing credits stay blank. | Create and verify FLAC copies, then play them in the workbench. |

## Made for a music-library workflow

- **A focused, reactive workbench** — switch between single-track and batch work, see queue status and progress, and review each track before creating.
- **A real file picker** — choose local audio in the browser or drag files into the app. Batch uploads are bounded to three at a time.
- **Unicode-safe on Windows** — non-English artist, title, and file-path text survives metadata lookup and display.
- **Your music stays yours** — uploaded source files are handled locally and removed from temporary storage after four hours or when the app closes.
- **Useful suggestions, not invented credits** — YouTube Music, existing file tags, and confident Apple iTunes Search matches help fill available metadata. Composer, writer, and publisher details are never guessed.
- **A permanent, visible destination** — new FLACs go to `Music\SongMetadataStudio\Output` by default, with an in-app folder field and **Open folder** action.
- **Listen before you leave** — successful single and batch exports include browser playback controls.
- **A one-click local app** — install a Desktop shortcut, open the workbench in Chrome, and shut the server down from the page.

## Get started on Windows

### First-time setup

Install these prerequisites:

| Requirement | Used for |
|:--|:--|
| Java 21 or newer | Running the packaged app |
| Maven 3.9+ | Building during setup if the packaged JAR is not present |
| FFmpeg and FFprobe on `PATH` | Reading media and creating/verifying FLACs |
| Python 3.9+ and `ytmusicapi` | YouTube Music search |
| Google Chrome (recommended) | Opening the app in its own browser window |

Install the Python dependency:

```powershell
python -m pip install -r requirements.txt
```

Then double-click **`Install-SongMetadataStudio.cmd`** in the project folder. Setup installs the app under:

```text
%LOCALAPPDATA%\Programs\SongMetadataStudio
```

It also creates a **Song Metadata Studio** shortcut on your Desktop. Double-click it whenever you want to open the app. The launcher waits for the local server to be ready before opening Chrome and reuses an existing healthy instance instead of starting another.

Use **Shut down app** in the left rail when you are finished. The server stops, temporary uploads are cleaned up, and saved FLACs remain in your output folder. Windows shutdown and restart naturally stop the local process too.

### Run from source

With the prerequisites above installed:

```powershell
mvn spring-boot:run
```

Open [http://127.0.0.1:8765](http://127.0.0.1:8765). The app binds to loopback only.

## A simple session

1. Pick **Single track** or **Batch studio**.
2. Browse for audio, drag it into the drop area, or enter a full local file path. Batch mode supports up to **100 tracks**, keeps uploads limited to **512 MiB per file**, and uploads at most **three files concurrently**.
3. Find and select the correct release for each track. Review and edit the proposed metadata before creating.
4. Create the FLAC copies. The app verifies the output tags and leaves each source file untouched.
5. Play the result in the page or open the output folder to find it in Explorer.

Browser-selected files are uploaded to the local app's temporary area. Temporary uploads are cleaned up after four hours or when the app closes. Entered paths are processed from their existing location; originals are not moved or modified.

## Your output folder

By default, new FLACs are saved here:

```text
%USERPROFILE%\Music\SongMetadataStudio\Output
```

The exact resolved path is shown in the app. Use its folder field to choose a different destination, or set `SONGTAGGER_OUTPUT_DIRECTORY` before launching to change the default.

Existing files are **not overwritten by default**: a collision-safe numbered filename is used. Enable **Replace existing FLACs** only when you intend to replace an existing output. Generated FLACs are stored outside the temporary upload area and remain available after app shutdown or restart.

## Metadata, artwork, and playback

The app uses YouTube Music suggestions and existing source-file tags, with Apple iTunes Search enrichment when title, artist, and (where available) duration match confidently. Apple data can help with track and album names, genre, release date, and track/disc numbers. It does not provide complete composer, writer, or publisher credits, so missing credits remain blank.

Cover art from the selected YouTube Music result is embedded in the created FLAC. The built-in player streams only FLACs inside registered output folders; it does not accept arbitrary file paths.

## Privacy by design

- The app is a local desktop workbench, not a hosted service; it has no login or authentication.
- The server listens on `127.0.0.1`, not your network interface.
- Audio is processed locally. Selected audio is not uploaded to YouTube Music or Apple.
- Search terms and track details are sent to YouTube Music and Apple's public iTunes Search API; artwork is fetched from YouTube Music image hosts.
- Browser uploads use local temporary storage and are cleaned up separately from permanent FLAC outputs.
- The shutdown action is restricted to same-origin requests from the local machine.

**Keep the server on loopback. Do not expose it to your network or the internet.**

## Troubleshooting

| If… | Try… |
|:--|:--|
| The Desktop shortcut cannot start the app | Check that Java 21+, FFmpeg/FFprobe, and Python with `ytmusicapi` are installed and available. See the latest startup logs in `%LOCALAPPDATA%\Programs\SongMetadataStudio\logs`. |
| Port `8765` is already in use | If the app is already open, use that window. Otherwise close the process using the port and try again. The launcher will not take over another app's port. |
| You want to rebuild or refresh the shortcut | Close the app with **Shut down app**, then run `Install-SongMetadataStudio.cmd` again. |
| The app cannot find a media executable | Add it to `PATH`, or set `PYTHON_EXECUTABLE`, `FFMPEG_EXECUTABLE`, or `FFPROBE_EXECUTABLE` before starting the app. |
| Your output location is different | Check the **Output folder** field in the workbench; it displays the active destination. |

## Development checks

Run the backend tests and package the runnable JAR:

```powershell
mvn clean package
```

Run the focused frontend tests:

```powershell
node --test src/test/js/app.test.cjs
```

## License

MIT — see [LICENSE](LICENSE).
