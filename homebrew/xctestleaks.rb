class Xctestleaks < Formula
  desc "Memory leak detection tool for iOS XCTest"
  homepage "https://github.com/amanjeetsingh150/XCTestLeaks"
  url "https://github.com/amanjeetsingh150/XCTestLeaks/releases/download/v0.1.4/xctestleaks.tar"
  sha256 "6686ccec1507d8af498f08b07b71864a0b1ca4affb1e700718f59081bb14f4b9"
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
    assert_match "xctestleaks 0.1.4", shell_output("#{bin}/xctestleaks --version")
  end
end