import {
  requireNativeComponent,
  UIManager,
  Platform,
  type ViewStyle,
  NativeModules,
} from 'react-native';

const LINKING_ERROR =
  `The package 'replate-camera' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

type ReplateCameraProps = {
  // rect: Object;
  color: string;
  style: ViewStyle;
};

const ComponentName = 'ReplateCameraView';

export const ReplateCameraView =
  UIManager.getViewManagerConfig(ComponentName) != null
    ? requireNativeComponent<ReplateCameraProps>(ComponentName)
    : () => {
        throw new Error(LINKING_ERROR);
      };

export const ReplateCameraModule = NativeModules.ReplateCameraController
  ? NativeModules.ReplateCameraController
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

export function takePhoto(unlimited: Boolean): Promise<string> {
  return ReplateCameraModule.takePhoto(unlimited);
}

export function getPhotosCount(): Promise<number> {
  return ReplateCameraModule.getPhotosCount();
}

export function getRemainingAnglesToScan(): Promise<number> {
  return ReplateCameraModule.getRemainingAnglesToScan();
}

export function isScanComplete(): Promise<boolean> {
  return ReplateCameraModule.isScanComplete();
}

export function registerCompletedTutorialCallback(callback: () => void) {
  ReplateCameraModule.registerCompletedTutorialCallback(callback);
}

export function registerAnchorSetCallback(callback: () => void) {
  ReplateCameraModule.registerAnchorSetCallback(callback);
}

export function registerCompletedUpperSpheresCallback(callback: () => void) {
  ReplateCameraModule.registerCompletedUpperSpheresCallback(callback);
}

export function registerCompletedLowerSpheresCallback(callback: () => void) {
  ReplateCameraModule.registerCompletedLowerSpheresCallback(callback);
}

export function registerOpenedTutorialCallback(callback: () => void) {
  ReplateCameraModule.registerOpenedTutorialCallback(callback);
}

export function registerTooFarCallback(callback: () => void) {
  ReplateCameraModule.registerTooFarCallback(callback);
}

export function registerTooCloseCallback(callback: () => void) {
  ReplateCameraModule.registerTooCloseCallback(callback);
}

export function registerBackInRangeCallback(callback: () => void) {
  ReplateCameraModule.registerBackInRangeCallback(callback);
}

export function reset() {
  ReplateCameraModule.reset();
}
