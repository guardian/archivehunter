#!/usr/bin/env bash

#react-multistep does not support bundling with babel/webpack out of the box. So we manually compile it here.
# https://github.com/srdjan/react-multistep/issues/20

#NOTE: this expects to be run from npm as a script, and therefore have babel-cli on the path.
# Obviously, it must be run AFTER npm install, and BEFORE build.

cd node_modules/react-multistep
if [ -f src/index.jsx ]; then
    echo It looks like react-multistep has already been modified and compiled. Moving on.
    exit 0
fi

FILESTOFIX=('index')

for file in $FILESTOFIX; do #multistep.js does not exist from v3 onwards
    echo Fixing ${file}.js
    mv src/${file}.js src/${file}.jsx
    #this uses the existing .babelrc settings from the react-multistep package
    babel src/${file}.jsx -o src/${file}.js
done
