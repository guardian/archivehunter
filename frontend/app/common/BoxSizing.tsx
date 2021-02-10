import React from "react";
import {Grid, IconButton, makeStyles} from "@material-ui/core";
import {ChevronLeft, ChevronRight} from "@material-ui/icons";
import clsx from "clsx";

interface BoxSizingProps {
    justify: "right"|"left";
    onRightClicked: ()=>void;
    onLeftClicked: ()=>void;
}

const useStyles = makeStyles({
    rightAligned: {
        float: "right"
    },
    leftAligned: {
        float: "left"
    },
    container: {
        width: "max-content"
    },
    buttonSizing: {
        width: "18px",
        height: "18px"
    }
})

/**
 * a component that displays left/right chevron buttons for expanding or shrinking a panel
 * @param props
 * @constructor
 */
const BoxSizing:React.FC<BoxSizingProps> = (props) => {
    const classes = useStyles();

    return <Grid container className={clsx(
        props.justify=="right" ? classes.rightAligned : classes.leftAligned,
        classes.container)}
                 spacing={0}
                 direction="row">
        <Grid item>
            <IconButton onClick={props.onLeftClicked} className={classes.buttonSizing}>
                <ChevronLeft/>
            </IconButton>
        </Grid>
        <Grid item>
            <IconButton onClick={props.onRightClicked} className={classes.buttonSizing}>
                <ChevronRight/>
            </IconButton>
        </Grid>
    </Grid>
}

export default BoxSizing;