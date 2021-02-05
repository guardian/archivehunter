import {ThemeOptions} from "@material-ui/core/styles";

const customisedTheme:ThemeOptions = {
    palette: {
        type: "dark",
        primary: {
            light: "#FFFFFF",
            main: "#AAAAAA",
            dark: "#888888",
            contrastText: "#7986cb"
        },
        // secondary: {
        //
        // }
    },
    typography: {
        fontFamily: ["Verdana", "Arial", "Helvetica", "sans-serif"].join(",")
    }
};

export {customisedTheme}