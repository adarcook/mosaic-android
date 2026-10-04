"""Check the packaged APK rather than relying solely on source configuration."""
import argparse
import re
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
parser.add_argument('build_tools', type=Path)
args = parser.parse_args()
subprocess.run([str(args.build_tools / 'apksigner'), 'verify', '--verbose', str(args.apk)], check=True)
xml = subprocess.check_output([str(args.build_tools / 'aapt'), 'dump', 'xmltree', str(args.apk), 'AndroidManifest.xml'], text=True)
service = re.search(r'    E: service.*?(?=\n    E:|\Z)', xml, re.S)
assert service and 'SpeechDiagnosticService' in service[0], 'Speech service missing'
assert re.search(r'android:process.*":speech"', service[0]), 'Speech service not in private process'
assert re.search(r'android:exported.*\(type 0x12\)0x0\b', service[0]), 'Speech service exported'
with ZipFile(args.apk) as apk:
    libraries = [name for name in apk.namelist() if name.startswith('lib/') and name.endswith('.so')]
    assert 'lib/arm64-v8a/libmosaic_whisper.so' in libraries
    for name in libraries:
        assert 'vulkan' not in name.lower(), name
        data = apk.read(name)
        # ONNX Runtime contains optional-provider names even in CPU use;
        # a string alone does not establish an ELF dependency or activation.
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / Path(name).name
            library.write_bytes(data)
            dynamic = subprocess.check_output(['readelf', '-d', str(library)], text=True)
            assert not re.search(r'NEEDED.*libvulkan', dynamic, re.I), name
        if name.endswith('/libmosaic_whisper.so'):
            assert b'libvulkan.so' not in data and b'ggml_backend_vk_init' not in data, name
print('Verified signature, private :speech service, ARM64 native engine, and absence of Vulkan linkage.')
