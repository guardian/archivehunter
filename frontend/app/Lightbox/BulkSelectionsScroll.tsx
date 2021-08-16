import React, {useEffect, useState} from 'react';
import TimestampFormatter from "../common/TimestampFormatter";
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";
import axios from 'axios';
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import {LightboxBulk, LightboxBulkResponse} from "../types";
import {formatError} from "../common/ErrorViewComponent";
import {Grid, IconButton, LinearProgress, makeStyles, Tooltip, Typography} from "@material-ui/core";
import clsx from "clsx";
import {AirportShuttle, DeleteOutline, GetApp, Timelapse, WarningRounded} from "@material-ui/icons";

interface BulkSelectionsScrollProps {
    currentSelection?: string;
    onSelected: (newId:string|undefined)=>void;
    forUser: string;
    isAdmin: boolean;
    expiryDays: number;
    onError?: (desc:string)=>void;
}

const useStyles = makeStyles((theme)=>({
    dealWithLongNames: {
        "& p": {
            width: "282px",
            overflow: "hidden"
        }
    },
    entryView: {
        border: "1px solid black",
        overflow: "hidden",
        width: "140px",
        height: "150px",
        borderRadius: "10px",
        backgroundColor: "white",
        display: "inline-block",
        marginRight: "2em",
        marginBottom: "2em",
        padding: "0.4em",
        paddingTop: "0.2em"
    },
    entryTitle: {
        marginTop: "0",
        marginBottom: "0.2em",
        color: theme.palette.secondary.dark,
        fontSize: "0.8em",
        fontWeight: "bold",
        backgroundColor: "inherit",
        marginLeft: "0.2em",
        marginRight: "0.2em",
        height: "2.5em",
        overflow: "hidden",
    },
    bulkSelectionView: {
        height: "130px",
        width: "280px",
        overflow: "hidden",
        backgroundColor: theme.palette.primary.main,
        whiteSpace: "nowrap"
    },
    bulkSelectionScroll: {
        overflowY: "hidden",
        overflowX: "auto",
        width: "max-content"
    },
    clickable: {
        cursor: "pointer",
    },
    entryThumbnailShadow: {
        boxShadow: "2px 2px 6px black"
    },
    entryViewSelected: {
        borderColor: "white !important",
        boxShadow: "3px 3px 5px black"
    },
    dontExpand: {
        marginBottom: 0,
        marginTop: 0,
    },
    black: {
        color: "black",
        backgroundColor: "inherit"
    },
    small: {
        "& p": {
            fontSize: "0.7em"
        }
    },
    bulkDownloadLink: {
        fontSize: "0.8em",
        zIndex: 999,
        float: "left"
    },
    redoRestoreLink: {
        fontSize: "0.8em",
        zIndex: 999,
        float: "right"
    },
    runOnText: {
        display: "inline"
    },
    warningIcon: {
        color: theme.palette.warning.dark
    }
}));

const BulkSelectionsScroll:React.FC<BulkSelectionsScrollProps> = (props) => {
    const [bulkSelections, setBulkSelections] = useState<LightboxBulk[]>([]);
    const [loading, setLoading] = useState(true);
    const classes = useStyles();

    const nameExtractor = /^([^:]+):(.*)$/;

    const extractNameAndPathArray = (str:string) => {
        const result = nameExtractor.exec(str);
        if(result){
            return ({name: result[1], pathArray: result[2].split("/")})
        } else {
            return ({name: str, pathArray: []})
        }
    }

    const bulkSearchDeleteRequested = async (entryId:string) => {
        try {
            await axios.delete("/api/lightbox/"+props.forUser+"/bulk/" + entryId);
            console.log("lightbox entry " + entryId + " deleted.");
            //if we are deleting the current selection, the update the selection to undefined otherwise do a no-op update
            //to trugger reload
            const updatedSelected = props.currentSelection===entryId ? undefined : props.currentSelection;

            setBulkSelections((prevState) => prevState.filter(entry=>entry.id!==entryId));
            props.onSelected(updatedSelected);
        } catch(err) {
            console.error(err);
            if(props.onError) props.onError(formatError(err, false));
        }
    }


    const loadData = async () => {
        setLoading(true);
        try {
            const bulkSelections = await axios.get<LightboxBulkResponse>("/api/lightbox/" + props.forUser + "/bulks");
            setBulkSelections(bulkSelections.data.entries);
            setLoading(false);
        } catch(err) {
            setLoading(false);
            console.error("Could not load in bulks: ", err);
            if(props.onError) props.onError(formatError(err, false));
        }
    }

    useEffect(()=>{
        loadData();
    }, [props.forUser]);

    const initiateDownloadInApp = (entryId:string) => {
        axios.get("/api/lightbox/bulk/appDownload/" + entryId, )
            .then(result=>{
                window.location.href = result.data.objectId;
            }).catch(err=>{
                console.error(err);
                if(props.onError) props.onError(formatError(err,false));
        })
    }

    const initiateRedoBulk = (entryId:string) => {
        axios.put("/api/lightbox/" + props.forUser + "/bulk/redoRestore/" + entryId).then(response=>{
            console.log(response.data);
        }).catch(err=>{
            console.error(err);
            if(props.onError) props.onError(formatError(err, false));
        })
    }

    const showExpiryWarning = (addedAt:string) => {
        try {
            let date = Date.now();
            let addedTime = Date.parse(addedAt);
            let setting = props.expiryDays * 86400000;
            return ((date - addedTime) > setting);
        } catch (err) {
            console.error("could not set expiry warning: ", err);
            if(props.onError) props.onError("An internal error occurred calculating expiry time");
            return false
        }
    }

    return <div className={classes.bulkSelectionScroll}>
        {
            loading ? <LinearProgress/> : bulkSelections.map((entry,idx)=>{
                const bulkInfo = extractNameAndPathArray(entry.description);
                const baseClasses = [
                    classes.entryView,
                    classes.bulkSelectionView,
                    classes.clickable
                ];
                const classList = props.currentSelection === entry.id ? baseClasses : baseClasses.concat(classes.entryThumbnailShadow);

                return <div className={clsx(classList)} onClick={()=>props.onSelected(entry.id)} key={idx}>
                    <Typography className={clsx(classes.entryTitle, classes.dontExpand)}>
                        <FontAwesomeIcon style={{marginRight: "0.5em"}} icon="hdd"/>{bulkInfo.name}
                    </Typography>
                    <Typography className={clsx(classes.black, classes.small, classes.dontExpand, classes.dealWithLongNames)}>
                        <FontAwesomeIcon style={{marginRight: "0.5em"}} icon="folder"/>
                        {bulkInfo.pathArray.length>0 ? bulkInfo.pathArray.slice(-1) : ""}
                    </Typography>
                    <Typography className={clsx(classes.black, classes.small, classes.dontExpand)}>
                        <TimestampFormatter relative={true} value={entry.addedAt} className={classes.runOnText}/>
                    </Typography>
                    <Grid container justify="space-between" alignItems="center">
                        <Grid item>
                            <Typography className={clsx(classes.black, classes.small, classes.dontExpand)}>
                                <FontAwesomeIcon style={{marginRight: "0.5em"}} icon="list-ol"/>{entry.availCount} items
                            </Typography>
                        </Grid>
                        <Grid>
                            <Grid container justify="flex-end" alignItems="center">
                                {
                                    showExpiryWarning(entry.addedAt) ?
                                        <Tooltip title="Some or all of the media may have returned to the deep archive and therefore be unavailable.">
                                            <WarningRounded className={classes.warningIcon}/>
                                        </Tooltip> : null
                                }
                                <Grid item>
                                    <Tooltip title="Download in app">
                                        <IconButton onClick={(evt)=>{
                                            evt.preventDefault();
                                            initiateDownloadInApp(entry.id)
                                        }}>
                                            <GetApp/>
                                        </IconButton>
                                    </Tooltip>
                                </Grid>
                                {
                                    props.isAdmin ? <Grid item>
                                        <Tooltip title="Re-do restore of folder">
                                            <IconButton onClick={(evt)=>{
                                                evt.preventDefault();
                                                initiateRedoBulk(entry.id);
                                            }}>
                                                <AirportShuttle/>
                                            </IconButton>
                                        </Tooltip>
                                    </Grid> : null
                                }
                                <Grid item>
                                    <Tooltip title="Remove this bulk from your lightbox">
                                        <IconButton style={{float: "right"}} onClick={(evt)=>{
                                            evt.stopPropagation();
                                            bulkSearchDeleteRequested(entry.id);
                                        }}>
                                            <DeleteOutline style={{color: "red"}}/>
                                        </IconButton>
                                    </Tooltip>
                                </Grid>
                            </Grid>
                        </Grid>
                    </Grid>
                </div>
            })
        }
    </div>

}

export default BulkSelectionsScroll;