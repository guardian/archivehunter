import React, {useState} from "react";
import {LightboxEntry, ObjectGetResponse, RestoreStatus, StylesMap} from "../types";
import {CircularProgress, Divider, Grid, Icon, IconButton, makeStyles, Tooltip, Typography} from "@material-ui/core";
import TimestampFormatter from "../common/TimestampFormatter";
import RestoreStatusComponent from "./RestoreStatusComponent";
import {baseStyles} from "../BaseStyles";
import {IconProp} from "@fortawesome/fontawesome-svg-core";
import clsx from "clsx";
import RestoreStatusIndicator from "./RestoreStatusIndicator";
import {AirportShuttle, GetApp, YoutubeSearchedFor} from "@material-ui/icons";
import axios from "axios";
import {formatError} from "../common/ErrorViewComponent";

interface LightboxDetailsInsertProps {
    lightboxEntry: LightboxEntry;
    archiveEntryId?: string;
    archiveEntryPath?: string;
    checkArchiveStatusClicked: ()=>void;
    redoRestoreClicked: (entryId:string)=>void;
    showingArchiveSpinner: boolean;
    user: string;
    onError?: (errorDesc:string) => void;
}

const useStyles = makeStyles((theme)=>Object.assign({
    smallSpinner: {
        width: "20px",
        height: "20px"
    },
    runOnText: {
        display: "inline"
    },
    nicelyAlignedIcon: {
        marginTop: "auto",
        marginBottom: "auto"
    }
} as StylesMap, baseStyles));

/**
 * this describes an "insert" into the standard entry details view, to provide lightbox-specific data
 */
const LightboxDetailsInsertImpl:React.FC<LightboxDetailsInsertProps> = (props) => {
    const [downloadUrl, setDownloadUrl] = useState<string|undefined>();

    const classes = useStyles();

    // const displayInfo = (status:string) => {
    //     if(status=='RS_UNNEEDED') {
    //         return "Not in deep freeze";
    //     } else {
    //         return undefined;
    //     }
    // }

    const displayRedo = (status:RestoreStatus) => {
        switch(status) {
            case "RS_PENDING":
            case "RS_UNDERWAY":
            case "RS_UNNEEDED":
                return false;
            case "RS_ERROR":
            case "RS_ALREADY":
            case "RS_SUCCESS":
                return true;
        }
    }

    const isDownloadable = () => {
        return props.lightboxEntry.restoreStatus=="RS_SUCCESS" || props.lightboxEntry.restoreStatus=="RS_UNNEEDED" || props.lightboxEntry.restoreStatus=="RS_ALREADY"
    }

    const doDownload  = async () => {
        try {
            const response = await axios.get<ObjectGetResponse<string>>("/api/download/" + props.archiveEntryId);
            setDownloadUrl(response.data.entry);
        } catch(err) {
            console.error("Could not get download URL: ", err);
            props.onError ? props.onError(formatError(err, false)) : null;
        }
    }

    return <div className={classes.centered}>
        {
            downloadUrl ? <iframe src={downloadUrl} style={{display:"none"}}/> : null
        }
        <span style={{display: "block"}}>
            <Typography className={classes.runOnText}>Added to lightbox&nbsp;</Typography>
            <TimestampFormatter className={classes.runOnText} relative={true} value={props.lightboxEntry.addedAt}/>
        </span>
        <RestoreStatusComponent
            status={props.lightboxEntry.restoreStatus}
            startTime={props.lightboxEntry.restoreStarted}
            completed={props.lightboxEntry.restoreCompleted}
            expires={props.lightboxEntry.availableUntil}
            hidden={props.lightboxEntry.restoreStatus==="RS_UNNEEDED"}
        />
        <Grid container direction="row" justify="space-between" spacing={3}>
            <Grid item className={classes.nicelyAlignedIcon} style={{marginLeft: "auto"}}>
                <RestoreStatusIndicator entry={props.lightboxEntry}/>
            </Grid>
            <Grid item>
                <Grid container direction="row" justify="center">

                    <Grid item>
                        <Tooltip title="Re-check archive status">
                                <IconButton onClick={props.checkArchiveStatusClicked}>
                                <YoutubeSearchedFor/>
                            </IconButton>
                        </Tooltip>
                    </Grid>
                    <Grid item>
                        {
                            displayRedo(props.lightboxEntry.restoreStatus) ?
                                <Tooltip title="Retry restore">
                                    <IconButton onClick={()=>props.archiveEntryId ? props.redoRestoreClicked(props.archiveEntryId) : null}>
                                        <AirportShuttle/>
                                    </IconButton>
                                </Tooltip> : null
                        }
                        {
                            props.showingArchiveSpinner ? <CircularProgress className={classes.smallSpinner}/> : null
                        }
                    </Grid>
                    <Grid item>
                        {
                            isDownloadable() ? <Tooltip title="Download just this file">
                                <IconButton onClick={doDownload}>
                                    <GetApp/>
                                </IconButton>
                            </Tooltip> : null
                        }
                    </Grid>
                </Grid>
            </Grid>
        </Grid>
        {
            props.lightboxEntry.availableUntil ? <>
                <Typography className={classes.runOnText}>
                    Available for
                </Typography>
                <TimestampFormatter relative={true} value={props.lightboxEntry.availableUntil} className={classes.runOnText}/>
            </> : <Typography className={classes.runOnText}>Available indefinitely</Typography>
        }

        {/*<Typography className={clsx(classes.centered, classes.small)}>*/}
        {/*    <a style={{cursor: "pointer"}} onClick={props.checkArchiveStatusClicked}>Re-check</a>*/}
        {/*</Typography>*/}
        {/*<Typography className={clsx(classes.centered, classes.small)}>*/}
        {/*    <a style={{cursor: "pointer"}} onClick={()=>props.archiveEntryId ? props.redoRestoreClicked(props.archiveEntryId) : null}>*/}
        {/*        {displayRedo(props.lightboxEntry.restoreStatus)}*/}
        {/*    </a>*/}
        {/*    {*/}
        {/*        props.showingArchiveSpinner ? <CircularProgress className={classes.smallSpinner}/> : null*/}
        {/*    }*/}
        {/*</Typography>*/}
        {/*<AvailabilityInsert status={props.lightboxEntry.restoreStatus}*/}
        {/*                    availableUntil={props.lightboxEntry.availableUntil}*/}
        {/*                    hidden={shouldHideAvailability()}*/}
        {/*                    fileId={props.archiveEntryId}*/}
        {/*                    fileNameOnly={extractPath()}*/}
        {/*/>*/}
        {/*<Typography className={clsx(classes.centered, classes.small, classes.information)}>*/}
        {/*    {props.lightboxEntry.restoreStatus}*/}
        {/*</Typography>*/}
        {/*<Divider style={{display: shouldHideAvailability() ? "inherit" : "none" }}/>*/}
    </div>;
}

/**
 * this class is an error-catching wrapper around the functional component above
 */
class LightboxDetailsInsert extends React.Component<LightboxDetailsInsertProps, { lastError:string|undefined }> {
    constructor(props:LightboxDetailsInsertProps) {
        super(props);

        this.state = {
            lastError: undefined
        }
    }

    componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
        console.error("Could not load lightbox details insert: ", error);
        console.error(errorInfo);
    }

    static getDerivedStateFromError(err:Error) {
        return {lastError: err.toString()};
    }

    render() {
        if(this.state.lastError) return <Typography>Lightbox details couldn't load, please see the console for details</Typography>
        return (
            <LightboxDetailsInsertImpl {...this.props}/>
        );
    }
}
export default LightboxDetailsInsert;