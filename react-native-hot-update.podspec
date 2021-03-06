require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-hot-update"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.description  = <<-DESC
                  react-native-hot-update
                   DESC
  s.homepage     = "https://github.com/liuminghui2017/react-native-hot-update"
  s.license      = "MIT"
  # s.license    = { :type => "MIT", :file => "FILE_LICENSE" }
  s.authors      = { "rickl" => "511189918@qq.com" }
  s.platforms    = { :ios => "9.0", :tvos => "10.0" }
  s.source       = { :git => "https://github.com/liuminghui2017/react-native-hot-update.git", :tag => "#{s.version}" }

  s.source_files = 'ios/HotUpdate/*.{h,m}'
  s.public_header_files = ['ios/HotUpdate/HotUpdate.h']
  s.requires_arc = true

  s.dependency "React"
  s.dependency 'SSZipArchive', '~> 2.1'
	
  # s.dependency "..."
end

