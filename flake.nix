{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";

    clojure-nix-locker.url = "github:bevuta/clojure-nix-locker";
    clojure-nix-locker.inputs.nixpkgs.follows = "nixpkgs";
    clojure-nix-locker.inputs.flake-utils.follows = "flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, clojure-nix-locker, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        lib = nixpkgs.lib;
        pkgs = import nixpkgs { inherit system; config.allowUnfree = true; };
        jdk = pkgs.graalvmPackages.graalvm-ce;
        clojure = (pkgs.clojure.override { inherit jdk; });
        prspct-clojure-nix-locker = clojure-nix-locker.lib.customLocker {
          inherit pkgs;
          command = "${clojure}/bin/clojure -T:build ci-light";
          lockfile = "./prspct-deps.lock.json";
          src = ./prspct;
        };
      in rec {
        packages.prspct = pkgs.stdenv.mkDerivation {
          pname = "prspct";
          version = "0.0";

          src = ./prspct;

          nativeBuildInputs = [
            pkgs.graalvmPackages.graalvm-ce
            pkgs.makeWrapper
            clojure
            pkgs.git
            pkgs.openssh
            # Breaks on macos
            # pkgs.breakpointHook
          ];

          buildPhase = ''
            source ${prspct-clojure-nix-locker.shellEnv}

            ${clojure}/bin/clojure -T:build ci
          '';

          installPhase = ''
            mkdir -p $out/bin
            mv target/org.net-perspective/prspct-*-SNAPSHOT $out/bin/prspct
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
              pkgs.graphviz
              pkgs.zip
              pkgs.openssh
              # `clojure-nix-locker` script
              prspct-clojure-nix-locker.locker
            ];
          };
        };
      });
}
