var webpack = require('webpack');
var path = require('path');
var TerserPlugin = require('terser-webpack-plugin');

var BUILD_DIR = path.resolve(__dirname + "/..", 'public/javascripts');
var APP_DIR = path.resolve(__dirname, 'app');

var config = {
    entry: APP_DIR + '/index.tsx',
    output: {
        path: BUILD_DIR,
        filename: 'bundle.js'
    },
    resolve: {
        extensions: [".ts", ".tsx", ".js", ".jsx"],
    },
    optimization: {
        minimizer: [new TerserPlugin()]
    },
    plugins: [
        new webpack.ProvidePlugin({
            process: 'process/browser',
        }),
    ],
    module : {
        rules : [
            {
                test : /\.[tj]sx?$/,
                include : APP_DIR,
                loader : 'ts-loader'
            },
            {
                test: /\.css$/,
                include: [
                    path.join(__dirname, "app/"),
                    path.join(__dirname, "node_modules/react-table")
                ],
                use: [
                    "style-loader",
                    "css-loader",
                    {
                        loader: "@teamsupercell/typings-for-css-modules-loader",
                    },
                ],
            },
            {
                test : /react-multistep\/.*\.js/,
                loader: 'ts-loader'
            },
            {
                enforce: "pre",
                test: /\.js$/,
                exclude: /node_modules/,
                loader: "source-map-loader",
            },
        ]
    }
};

module.exports = config;