# react-native-hot-update

## 说明
鉴于code-push在国内不稳定，故自己撸了个热更功能，本模块不提供服务端实现
参考资料：
1. [ReactNative增量升级方案](https://github.com/cnsnake11/blog/blob/master/ReactNative开发指导/ReactNative增量升级方案.md)
2. [React Native 实现热部署、差异化增量热更新](https://blog.csdn.net/csdn_aiyang/article/details/78328000)
3. [Code Push源码](https://github.com/microsoft/react-native-code-push)

### Manual installation


#### iOS

1. In XCode, in the project navigator, right click `Libraries` ➜ `Add Files to [your project's name]`
2. Go to `node_modules` ➜ `react-native-hot-update` and add `HotUpdate.xcodeproj`
3. In XCode, in the project navigator, select your project. Add `libHotUpdate.a` to your project's `Build Phases` ➜ `Link Binary With Libraries`
4. Run your project (`Cmd+R`)<

#### Android

1. Open up `android/app/src/main/java/[...]/MainApplication.java`
  - Add `import com.rickl.reactlibrary.hotupdate.HotUpdatePackage;` to the imports at the top of the file
  - Add `new HotUpdatePackage()` to the list returned by the `getPackages()` method
2. Append the following lines to `android/settings.gradle`:
  	```
  	include ':react-native-hot-update'
  	project(':react-native-hot-update').projectDir = new File(rootProject.projectDir, 	'../node_modules/react-native-hot-update/android')
  	```
3. Insert the following lines inside the dependencies block in `android/app/build.gradle`:
  	```
      compile project(':react-native-hot-update')
  	```


## Usage
```javascript
import HotUpdate from 'react-native-hot-update';

// TODO: What to do with the module?
HotUpdate;
```
