import React from 'react';
import { AppRegistry, StyleSheet, View } from 'react-native';
import { ReplateCameraView } from './src';

function MainApp() {
  return (
    <View style={styles.container}>
      <ReplateCameraView color="#32a852" style={styles.camera} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: 'transparent',
  },
  camera: {
    flex: 1,
  },
});

AppRegistry.registerComponent('main', () => MainApp);
