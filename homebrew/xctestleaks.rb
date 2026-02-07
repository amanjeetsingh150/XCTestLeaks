class Xctestleaks < Formula
  desc "Memory leak detection tool for iOS XCTest"
  homepage "https://github.com/amanjeetsingh150/XCTestLeaks"
  url "https://github.com/amanjeetsingh150/XCTestLeaks/releases/download/v0.1.2/xctestleaks.tar"
  sha256 "7f332a8be066d25fd8f57927d427e4b8433b70a57cb78e4b544772dc19f31d52"
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
    assert_match "xctestleaks 0.1.2", shell_output("#{bin}/xctestleaks --version")
  end
end