module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android/lib',
        packageImportPath: 'import com.replatecamera.ReplateCameraPackage;',
        packageInstance: 'new ReplateCameraPackage()',
      },
    },
  },
  dependencies: {
    expo: {
      platforms: {
        android: null,
      },
    },
  },
};
