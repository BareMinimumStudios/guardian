{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    nixpkgs-old.url = "github:NixOS/nixpkgs/25.11";
  };

  outputs = {
    nixpkgs,
    nixpkgs-old,
    ...
  } @ inputs: let
    lib = nixpkgs.lib;
    supportedSystems = ["x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin"];
    forEachSupportedSystem = f:
      lib.genAttrs supportedSystems (system:
        f {
          pkgs = import nixpkgs {inherit system;};
          pkgs-old = import nixpkgs-old {inherit system;};
        });
  in {
    devShells = forEachSupportedSystem ({
      pkgs,
      pkgs-old,
      ...
    }: let
      java17 = pkgs.openjdk17;
      java21 = pkgs.jetbrains.jdk-no-jcef-21;

      nativeBuildInputs =
        [
          java17
          java21
        ]
        ++ (with pkgs-old; [
          # Requires kotlin 2.2
          kotlin
          kotlin-language-server
        ]);

      buildInputs = with pkgs; [
        libGL
        glfw3-minecraft
        flite
        libpulseaudio
      ];
    in {
      default = pkgs.mkShell {
        inherit nativeBuildInputs buildInputs;

        env = {
          LD_LIBRARY_PATH = lib.makeLibraryPath buildInputs;
          JAVA_HOME = "${java21.home}";
          JDK17 = "${java17.home}";
          JDK21 = "${java21.home}";
        };
      };
    });
  };
}
