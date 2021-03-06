import {Theme} from "@material-ui/core";
import {Styles} from "@material-ui/core/styles/withStyles"

/**
 * the guts of the old main.css.  These are common styles that get applied across lots of different components.
 * they can be loaded into a per-component stylesheet by using Object.assign({specific-styles...}, baseStyles);
 * having imported baseStyles from this file.
 */
const baseStyles:Styles<Theme,{}> = {
    root: {
       "& a": {
           textDecoration: "none",
           color: "#AAAAAA"
       }
    },
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
    centered: {
        marginLeft: "auto",
        marginRight: "auto",
        width: "66%",
        textAlign: "center",
    },
    inlineThrobber: {
        marginTop: "-1em",
        marginBottom: "-0.5em"
    },
    clickable: {
        cursor: "pointer"
    }

}

export {baseStyles};