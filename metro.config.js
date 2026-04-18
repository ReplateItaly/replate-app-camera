let metroConfig;

try {
  metroConfig = require('@react-native/metro-config');
} catch (error) {
  metroConfig = require('./example/node_modules/@react-native/metro-config');
}

const { getDefaultConfig, mergeConfig } = metroConfig;

const config = {
  watchFolders: [__dirname],
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
