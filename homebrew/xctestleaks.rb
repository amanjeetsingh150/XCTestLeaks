class Xctestleaks < Formula
  desc "Memory leak detection tool for iOS XCTest"
  homepage "https://github.com/amanjeetsingh150/XCTestLeaks"
  url "https://github.com/amanjeetsingh150/XCTestLeaks/releases/download/v0.1.3/xctestleaks.tar"
  sha256 "206a100d41060a98e98d4a5383baedac32e39237ccf74f9d9b5ca461d4d4eae4"
  license "Apache-2.0"

  depends_on "openjdk"

  def install
    libexec.install Dir["*"]

    # Rewrite the shell wrapper to use Homebrew's JDK
    (bin/"xctestleaks").write_env_script(
      libexec/"bin/xctestleaks",
      JAVA_HOME: Formula["openjdk"].opt_prefix
    )
  end

  test do
    assert_match "xctestleaks 0.1.3", shell_output("#{bin}/xctestleaks --version")
  end
end