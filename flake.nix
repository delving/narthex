{
  description = "Narthex dev shell (sbt + JDK 21 + node for the editor build)";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAll = f: nixpkgs.lib.genAttrs systems (s: f nixpkgs.legacyPackages.${s});
    in {
      devShells = forAll (pkgs: {
        default = pkgs.mkShell {
          # build.sbt compiles with -release 21; sbt must run on the same JDK.
          # maven: sip-core (../sip-creator) must be `mvn install`ed into ~/.m2 first.
          packages = [ pkgs.jdk21 (pkgs.sbt.override { jre = pkgs.jdk21; }) pkgs.nodejs
                       (pkgs.maven.override { jdk_headless = pkgs.jdk21; }) ];
        };
      });
    };
}
