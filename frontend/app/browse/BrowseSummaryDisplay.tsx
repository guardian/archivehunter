import React from "react";
import {Grid, IconButton, makeStyles, Tooltip, Typography} from "@material-ui/core";
import clsx from "clsx";
import {HomeRounded, Storage, WarningRounded} from "@material-ui/icons";
import PathDisplayComponent from "./PathDisplayComponent";
import RefreshButton from "../common/RefreshButton";
import BytesFormatter from "../common/BytesFormatter";
import BulkLightboxAdd from "./BulkLightboxAdd";
import {StylesMap} from "../types";
import {baseStyles} from "../BaseStyles";

interface BrowseSummaryDisplayProps {
    goToRootCb:()=>void;
    collectionName?:string;
    path?:string;
    parentIsLoading:boolean;
    refreshCb:()=>void;
    totalHits:number;
    totalSize:number;
}

const useStyles = makeStyles((theme)=>(Object.assign({
    summaryIcon: {
        marginRight: "0.1em",
        verticalAlign: "bottom"
    },
    collectionNameText: {
        fontWeight: "bold"
    },
    summaryBoxElement: {
        marginTop: "auto",
        marginBottom: "auto",
        marginLeft: "1em"
    },
    warningIcon: {
        color: theme.palette.warning.dark
    }
} as StylesMap, baseStyles)));

/**
 * This component abstracts the display layout for the BrowseSummary element, so it can be re-used with slightly
 * different elements between the deleted items admin view and the archive browse view
 * @param props
 * @constructor
 */
const BrowseSummaryDisplay:React.FC<BrowseSummaryDisplayProps> = (props) =>{
    const classes = useStyles();

    return <div className="browse-path-summary">
        <Grid container direction="column" alignContent="center" justify="center">
            <Grid item className={clsx(classes.summaryBoxElement,classes.centered)}>
                <Grid container direction="row"  justify="center" alignContent="space-around" alignItems="center">
                    {props.path ?
                        <Grid item>
                            <IconButton onClick={props.goToRootCb}>
                                <HomeRounded/>
                            </IconButton>
                        </Grid> : null
                    }
                    {props.collectionName ? <Grid item>
                        <Typography className={classes.collectionNameText}>
                            <Storage className={classes.summaryIcon}/>
                            {props.collectionName}
                        </Typography>
                    </Grid> : null
                    }
                </Grid>
            </Grid>
            {
                props.path ? <Grid item className={classes.summaryBoxElement}>
                    <PathDisplayComponent path={props.path}/>
                </Grid> : null
            }

            <Grid item>
                <Grid container direction="row" spacing={1} alignItems="center" >
                    <Grid item>
                        <RefreshButton isRunning={props.parentIsLoading}
                                       clickedCb={props.refreshCb}/>
                    </Grid>
                    <Grid item>
                        <Typography>Total of {props.totalHits} items occupying <BytesFormatter value={props.totalSize}/></Typography>
                    </Grid>
                    {
                        props.children
                    }
                </Grid>
            </Grid>

        </Grid>
    </div>
}

export default BrowseSummaryDisplay;