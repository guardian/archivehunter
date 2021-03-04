import React, {ReactElement, ReactFragment} from "react";
import {Grid, IconButton, makeStyles} from "@material-ui/core";
import {Launch} from "@material-ui/icons";

interface FlexMetadataEntryProps {
    label: string;
    value: string|ReactElement;
    className?: string;
    icon?: ReactElement;
    callout?: ()=>void;   //callback for linking
}

const useStyles = makeStyles((theme)=> ({
    title: {
        fontWeight: "bold",
        fontSize: "1.4em",
        margin: "0",
    },
    info: {
        fontWeight: "normal",
        fontSize: "1.2em",
        verticalAlign: "super",
        margin: "0"
    },
    iconContainer: {
        height: "24px",
        marginRight: "0.3em"
    }
}));

/**
 * a single entry in a flexbox grid style metadata table
 * @param props
 * @constructor
 */
const FlexMetadataEntry:React.FC<FlexMetadataEntryProps> = (props) => {
    const classes = useStyles();

    return <Grid item className={props.className}>
        <p className={classes.title}>{props.label}</p>
        {
            props.icon ? <span className={classes.iconContainer}>{props.icon}</span> : undefined
        }
        <span className={classes.info}>{props.value}</span>
        {
            props.callout ? <IconButton onClick={props.callout}><Launch/></IconButton> : undefined
        }
    </Grid>
}

export default FlexMetadataEntry;