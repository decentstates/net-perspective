{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    # TODO: Updated clojure-nix-locker
    nixpkgs-clojure-nix-locker.url = "github:NixOS/nixpkgs/nixos-25.05";
    flake-utils.url = "github:numtide/flake-utils";

    clojure-nix-locker.url = "github:bevuta/clojure-nix-locker";
    clojure-nix-locker.inputs.nixpkgs.follows = "nixpkgs-clojure-nix-locker";
    clojure-nix-locker.inputs.flake-utils.follows = "flake-utils";
  };

  outputs = { self, nixpkgs, nixpkgs-clojure-nix-locker, flake-utils, clojure-nix-locker, ... }:
    {
        templates.default = {
          path = ./prspct-flake-template;
          description = "Ready-made environment for using prspct";
        };
    } //
    flake-utils.lib.eachDefaultSystem (system:
      let
        lib = nixpkgs.lib;
        pkgs = import nixpkgs { inherit system; };
        pkgs-clojure-nix-locker = import nixpkgs-clojure-nix-locker { inherit system; };
        jdk = pkgs.graalvmPackages.graalvm-ce;
        clojure = (pkgs.clojure.override { inherit jdk; });
        hugo-shibui-theme = pkgs.fetchFromGitHub {
          owner = "ntk148v";
          repo = "shibui";
          rev = "8d4a86ad2f9d9677e1d2bd535c41168a008feeb1";
          hash = "sha256-OVDn5csKMY8pWg3JvR/ojyIDlerd+EdZnfeHAUTVanc=";
        };
        prspct-clojure-nix-locker = clojure-nix-locker.lib.customLocker {
          pkgs = pkgs-clojure-nix-locker;
          command = "${clojure}/bin/clojure -T:build ci-light";
          lockfile = "./prspct-deps.lock.json";
          src = ./prspct;
        };
      in rec {
        packages.hugo-themes = pkgs.stdenv.mkDerivation {
          name = "hugo-themes";
          dontUnpack = true;
          installPhase = ''
            mkdir -p $out
            ln -s ${hugo-shibui-theme} $out/shibui
          '';
        };

        packages.hugo-with-themes = pkgs.symlinkJoin {
          name = "hugo-with-themes";
          paths = [
            pkgs.hugo
          ];
          
          buildInputs = [ pkgs.makeWrapper ];

          postBuild = ''
            wrapProgram $out/bin/hugo --set HUGO_themesDir '${self.packages.${system}.hugo-themes}'
          '';
        };

        packages."net-perspective-org-website" = pkgs.stdenv.mkDerivation {
          pname = "net-perspective-org-website";
          src = ./net-perspective.org;

          nativeBuildInputs = [
            # self.packages.${system}.hugo-with-themes
            pkgs.hugo
            # pkgs.git
          ];

          buildPhase = ''
            hugo
          '';

          installPhase = ''
            mkdir -p $out
            mv public $out
          '';
        };

        packages.prspct = pkgs.stdenv.mkDerivation {
          pname = "prspct";
          version = "0.3.0";

          src = ./prspct;

          nativeBuildInputs = [
            pkgs.graalvmPackages.graalvm-ce
            pkgs.makeWrapper
            clojure
            pkgs.git
            pkgs.openssh
            # Breaks on macos:
            # pkgs.breakpointHook
          ];

          buildPhase = ''
            source ${prspct-clojure-nix-locker.shellEnv}

            ${clojure}/bin/clojure -T:build ci
          '';

          installPhase = ''
            mkdir -p $out/bin
            mv target/org.net-perspective/prspct $out/bin/prspct
          '';
        };

        formatter = pkgs.nixfmt-rfc-style;
        devShells = {
          default = pkgs.mkShellNoCC {
            packages = [
              jdk
              clojure
              pkgs.babashka
              pkgs.clj-kondo
              pkgs.cljfmt
              pkgs.graphviz
              pkgs.zip
              pkgs.openssh
              pkgs.hugo
              # self.packages.${system}.hugo-with-themes
              # `clojure-nix-locker` script
              prspct-clojure-nix-locker.locker
            ];
          };
        };
      });
}
