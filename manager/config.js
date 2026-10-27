// Public OAuth "Web application" client id (not a secret). Allowed JavaScript origins are set in
// Google Cloud Console: https://omrihak.github.io and http://localhost:8000.
window.MANAGER_CONFIG = {
  clientId: "353844223665-sa4jbsu2d8ik1d9fc5renfqnngfjgfri.apps.googleusercontent.com",
  rootFolderName: "Tap and Play",
  // Signature checksum of the release key the player APK on GitHub Releases is signed with (setup.html puts it in
  // the setup QR code). Filled in by the maintainer from the output of scripts/make-release-key.sh. Not a secret.
  releaseSignatureChecksum: "",
};
