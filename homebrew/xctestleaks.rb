class Xctestleaks < Formula
  desc "Memory leak detection tool for iOS XCTest"
  homepage "https://github.com/amanjeetsingh150/XCTestLeaks"
  url "https://github.com/amanjeetsingh150/XCTestLeaks/releases/download/v0.1.0/xctestleaks.tar"
  sha256 "339b3dee8e4db30335545530ed799ff1a21d9a99fc4cab8cfe2cfd89f4912426"
  license "MIT"

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
    assert_match "xctestleaks 0.1.0", shell_output("#{bin}/xctestleaks --version")
  end
end