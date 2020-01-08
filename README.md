# react-native-hot-update

## 说明
修改CodePush客户端源码，实现自己的服务器端

参考资料：
1. [ReactNative增量升级方案](https://github.com/cnsnake11/blog/blob/master/ReactNative开发指导/ReactNative增量升级方案.md)
2. [React Native 实现热部署、差异化增量热更新](https://blog.csdn.net/csdn_aiyang/article/details/78328000)
3. [Code Push源码](https://github.com/microsoft/react-native-code-push)

### 手动集成
### >= RN 0.60.x
#### iOS
1. `cd ios && pod install && cd ..`
2. AppDelegate.m文件，引入头文件`#import <HotUpdate/HotUpdate.h>`
3. AppDelegate.m文件，didFinishLaunchingWithOptions生命周期函数中将
	```c
	return [[NSBundle mainBundle] URLForResource:@"main" withExtension:@"jsbundle"];
	```
	替换成

	```c
	return [HotUpdate bundleURL];
	```


#### Android
1. Open up `android/app/src/main/java/[...]/MainApplication.java`
   - Add `import com.rickl.reactlibrary.hotupdate.HotUpdate;` to the imports at the top of the file
   - 在`getPackages()`函数同层级下，增加下面方法
		```java
		@Override
		protected String getJSBundleFile() {
			return HotUpdate.getJSBundleFile();
		}

### <= RN 0.59.x

#### iOS

1. In XCode, in the project navigator, right click `Libraries` ➜ `Add Files to [your project's name]`
2. Go to `node_modules` ➜ `react-native-hot-update` and add `HotUpdate.xcodeproj`
3. In XCode, in the project navigator, select your project. Add `libHotUpdate.a` to your project's `Build Phases` ➜ `Link Binary With Libraries`
4. AppDelegate.m文件，引入头文件`#import <HotUpdate/HotUpdate.h>`
5. AppDelegate.m文件，didFinishLaunchingWithOptions生命周期函数中将
	```c
	jsCodeLocation = [[RCTBundleURLProvider sharedSettings] jsBundleURLForBundleRoot:@"index" fallbackResource:nil];
	```
	替换成

	```c
	jsCodeLocation = [HotUpdate bundleURL];
	```

#### Android

1. Open up `android/app/src/main/java/[...]/MainApplication.java`
  - Add `import com.rickl.reactlibrary.hotupdate.HotUpdate;` to the imports at the top of the file
  - Add `new HotUpdate(getApplicationContext(), BuildConfig.DEBUG)` to the list returned by the `getPackages()` method
	
   - 在`getPackages()`函数同层级下，增加下面方法
		```java
		@Override
		protected String getJSBundleFile() {
			return HotUpdate.getJSBundleFile();
		}
		```
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

class App extends React.Component {
	componentDidMount() {
		HotUpdate.setServerUrl(`http://xxx.com/project/hotupdate/${Platform.OS}`)
		HotUpdate.notifyAppReady()
	}

	render() {
		return (
			<View>
				<Button title="checkUpdate" onPress={this.checkUpdate} />
			</View>
		)
	}

	checkUpdate = () => {
		    let remotePackage = await HotUpdate.checkUpdate()
    if (remotePackage) {
      Alert.alert(
        '更新提示',
        remotePackage.description,
        [
          { text: '取消' },
          { 
            text: '更新', 
            onPress: () => {
              HotUpdate.download(remotePackage, ({totalBytes, receivedBytes}) => {
                this.setState({ progress: `${receivedBytes} / ${totalBytes}` })
              }).then(() => {
                Alert.alert('下载完成', '马上重启?',
                  [
                    { 
                      text: '稍后',
                      onPress: () => { HotUpdate.install(remotePackage, HotUpdate.InstallMode.ON_NEXT_RESUME) }
                    },
                    {
                      text: '现在',
                      onPress: () => { HotUpdate.install(remotePackage, HotUpdate.InstallMode.IMMEDIATE) }
                    }
                  ]
                )
              }).catch((e) => {
                alert('下载失败： ' + e.toString())
              })
            },
          },
        ]
      )
    } else {
      alert('暂无更新')
    }
	}
}

HotUpdate;
```
