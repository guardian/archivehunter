import {Theme} from "@material-ui/core";
import {Styles} from "@material-ui/core/styles/withStyles"

/**
 * the guts of the old main.css
 */
const baseStyles:Styles<Theme,{},string> = {
    smallIcon: {
        width: "32px"
    },
    tinyIcon: {
        width: "16px"
    },
    highlight: {
        color: "white"
    },
    inlineIcon: {
        marginRight: "0.4em"
    },
    errorText: {
        color: "darkred",
        fontWeight: "bold"
    },
    centered: {
        marginLeft: "auto",
        marginRight: "auto",
        width: "66%"
    }
}

export {baseStyles};