from pathlib import Path
import tempfile,zipfile,unittest
from verify_canary_artifact import verify

class CanaryArtifactTest(unittest.TestCase):
    def fixture(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);root=Path(temp.name)
        files=root/'files';files.mkdir()
        for name in ['build.apk','BUILD_INFO.txt','SHA256SUMS.txt','CANARY_CHANGELOG.md']:(files/name).write_bytes(name.encode())
        return root,files
    def archive(self,root,files,changed=False,extra=False):
        path=root/'archive.zip'
        with zipfile.ZipFile(path,'w') as bundle:
            for file in files.iterdir():bundle.writestr(file.name,b'changed' if changed and file.name.endswith('.apk') else file.read_bytes())
            if extra:bundle.writestr('../signing.p12',b'not permitted')
        return path
    def test_uploaded_package_matches(self):
        root,files=self.fixture();verify(self.archive(root,files),files)
    def test_changed_or_extra_files_are_rejected(self):
        for changed,extra in [(True,False),(False,True)]:
            root,files=self.fixture()
            with self.assertRaises(ValueError):verify(self.archive(root,files,changed,extra),files)
