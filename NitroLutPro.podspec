require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "NitroLutPro"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported, :visionos => 1.0 }
  s.source       = { :git => "https://github.com/mrousavy/nitro.git", :tag => "#{s.version}" }

  s.frameworks = 'Metal', 'MetalKit', 'UIKit', 'ImageIO', 'CoreGraphics', 'QuartzCore', 'AVFoundation', 'CoreImage'

  s.source_files = [
    # Implementation (Swift)
    "ios/**/*.{swift}",
    # Autolinking/Registration (Objective-C++)
    "ios/**/*.{m,mm}",
    # Implementation (C++ objects)
    "cpp/**/*.{hpp,cpp}",
  ]

  # Ship the .metal shader as a precompiled metallib inside a Pod resource bundle
  # so the host `.app` gets a `NitroLutPro.bundle/default.metallib` it can load
  # via `Bundle(for: MetalLutRenderer.self).url(forResource: "NitroLutPro", withExtension: "bundle")`.
  # This is a fallback for the runtime source-compile path in LutShaders.swift.
  s.resource_bundles = {
    "NitroLutPro" => ["ios/**/*.metal"],
  }

  load 'nitrogen/generated/ios/NitroLutPro+autolinking.rb'
  add_nitrogen_files(s)

  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'
  install_modules_dependencies(s)
end
