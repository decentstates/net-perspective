{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";
    flake-utils.url = "github:numtide/flake-utils";
    net-perspective.url = "git+https://git.sr.ht/~decentstates/net-perspective?ref=stable";
  };

  outputs = { self, nixpkgs, flake-utils, net-perspective, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        lib = nixpkgs.lib;
        pkgs = import nixpkgs { inherit system; };
      in rec {
        formatter = pkgs.nixfmt-rfc-style;
        devShells = {
          default = pkgs.mkShellNoCC {
            packages = [
              pkgs.openssh
              net-perspective.packages.${system}.prsp
            ];
          };
        };
      });
}
