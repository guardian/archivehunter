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
        secondary: {
            main: "#1565c0",
            light: "#5e92f3",
            dark: "#003c8f"
        }
    },
    typography: {
        fontFamily: ["Droid Sans","Verdana", "Arial", "Helvetica", "sans-serif"].join(",")
    }
};

export {customisedTheme}