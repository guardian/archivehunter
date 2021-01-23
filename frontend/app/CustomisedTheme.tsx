import {ThemeOptions} from "@material-ui/core/styles";

const customisedTheme:ThemeOptions = {
    palette: {
        type: "dark"
    },
    typography: {
        fontSize: 18,
        fontFamily: ["Verdana", "Arial", "Helvetica", "sans-serif"].join(",")
    }
};

export {customisedTheme}