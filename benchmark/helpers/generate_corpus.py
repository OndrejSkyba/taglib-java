#!/usr/bin/env python3
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def main():

    corpus = Path(os.environ.get('CORPUS', str(ROOT / 'work/write-corpus'))).absolute()
    sizes = [int(value) for value in os.environ.get('SIZES_MIB', '1,10,50').split(',')]
    if not sizes or any(value < 1 or value > 512 for value in sizes):
        raise SystemExit('SIZES_MIB must contain positive sizes no larger than 512 MiB.')
    if corpus.exists() or corpus.is_symlink():
        raise SystemExit(f'Existing corpus retained: {corpus}. Choose a new CORPUS directory to regenerate.')
    if not shutil.which('ffmpeg'):
        raise SystemExit('FFmpeg is required.')
    corpus.parent.mkdir(parents=True, exist_ok=True)
    formats = [
        ('mp3', 40000, ['-c:a', 'libmp3lame', '-b:a', '320k']),
        ('flac', 70000, ['-c:a', 'flac', '-sample_fmt', 's16', '-compression_level', '5']),
        ('ogg', 25000, ['-c:a', 'libvorbis', '-q:a', '6']),
        ('opus', 32000, ['-c:a', 'libopus', '-b:a', '256k', '-vbr', 'off']),
        ('m4a', 32000, ['-c:a', 'aac', '-b:a', '256k', '-movflags', '+faststart']),
        ('wav', 192000, ['-c:a', 'pcm_s16le']),
        ('aiff', 192000, ['-c:a', 'pcm_s16be', '-write_id3v2', '1']),
    ]
    selected = os.environ.get('FORMATS', '').split(',') if os.environ.get('FORMATS') else [item[0] for item in formats]
    if set(selected) - {item[0] for item in formats}:
        raise SystemExit('Unknown FORMATS selection.')
    formats = [item for item in formats if item[0] in selected]
    budget = sum(sizes) * len(formats) * 1024 * 1024
    if shutil.disk_usage(corpus.parent).free < budget * 2:
        raise SystemExit('Not enough free space for staged corpus generation.')
    recipe = {'schemaVersion': 1, 'ffmpegVersion': subprocess.check_output(['ffmpeg', '-version'], text=True).splitlines()[0],
              'source': 'seeded pink noise, 48kHz stereo; no input recordings', 'nominalSizesMiB': sizes, 'files': []}
    staging = Path(tempfile.mkdtemp(prefix='.write-corpus-', dir=corpus.parent))
    try:
        for extension, rate, options in formats:
            for size in sizes:
                target = size * 1024 * 1024
                duration = target / rate
                name = f'pink-{size:03d}mib.{extension}'
                output = staging / name
                for attempt in range(3):
                    command = ['ffmpeg', '-v', 'error', '-nostdin', '-y', '-f', 'lavfi', '-i',
                               f'anoisesrc=color=pink:amplitude=0.3:seed=314159:sample_rate=48000:duration={duration:.6f}',
                               '-ac', '2', '-map_metadata', '-1', '-metadata', 'title=Original benchmark title',
                               '-metadata', 'artist=TagLib write benchmark', '-metadata', 'album=Synthetic full-size corpus',
                               '-metadata', 'comment=Original short comment', *options, str(output)]
                    subprocess.run(command, check=True, timeout=900)
                    actual = output.stat().st_size
                    if target * 0.85 <= actual <= target * 1.15:
                        break
                    duration *= target / max(1, actual)
                if not target * 0.85 <= actual <= target * 1.15:
                    raise RuntimeError(f'{name} missed the nominal size range: {actual} bytes')
                recipe['files'].append({'name': name, 'bytes': actual, 'command': command[:-1] + [str(corpus / name)]})
                print(f'{name}: {actual} bytes', flush=True)
        if corpus.exists():
            raise RuntimeError('Corpus destination appeared during generation; retaining it.')
        staging.rename(corpus)
        recipe_path = corpus.with_name(corpus.name + '-recipe.json')
        recipe_path.write_text(json.dumps(recipe, indent=2) + '\n')
        print(f'Corpus: {corpus}\nRecipe: {recipe_path}')
    finally:
        if staging.exists():
            shutil.rmtree(staging)

if __name__ == "__main__":
    main()
