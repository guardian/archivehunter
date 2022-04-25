import React, {createRef, useState} from "react";
import {LightboxEntry, ObjectGetResponse, RestoreStatus, StylesMap} from "../types";
import {CircularProgress, Divider, Grid, Icon, IconButton, makeStyles, Tooltip, Typography} from "@material-ui/core";
import TimestampFormatter from "../common/TimestampFormatter";
import RestoreStatusComponent from "./RestoreStatusComponent";
import {baseStyles} from "../BaseStyles";
import RestoreStatusIndicator from "./RestoreStatusIndicator";
import {AirportShuttle, CheckCircle, GetApp, YoutubeSearchedFor} from "@material-ui/icons";
import axios, {AxiosResponse} from "axios";
import {baseName} from "../common/Fileinfo";

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
    },
    successIcon: {
        color: theme.palette.success.main,
        marginRight: "0.4em",
        verticalAlign: "middle"
    },
    smallText: {
        fontSize: "0.8em"
    }
} as StylesMap, baseStyles));

/**
 * this describes an "insert" into the standard entry details view, to provide lightbox-specific data
 */
const LightboxDetailsInsertImpl:React.FC<LightboxDetailsInsertProps> = (props) => {
    const [downloadUrl, setDownloadUrl] = useState<string|undefined>();
    const [downloading, setDownloading] = useState(false);
    const [downloadedTo, setDownloadedTo] = useState<string|undefined>();

    const classes = useStyles();

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


    const newStyleDownload = async (response: AxiosResponse<ObjectGetResponse<string>>) => {
        console.log("Using new file picker")
        // @ts-ignore
        const handle = await window.showSaveFilePicker({
            suggestedName: baseName(props.archiveEntryPath)
        });
        const writable = await handle.createWritable();
        const content = await fetch(response.data.entry);

        if(content.body) {
            setDownloading(true);
            //the pipeTo method will automatically close the writable stream
            await content.body.pipeTo(writable);
            const targetFile:File = await handle.getFile();
            setDownloadedTo(targetFile.name);
            setDownloading(false);
        } else {
            alert("No content was available to download");
        }
    }

    const oldStyleDownload = (response:AxiosResponse<ObjectGetResponse<string>>) => {
        console.log("Using legacy output");
        setDownloadUrl(response.data.entry);
        const triggerDownload = (ctr:number)=> window.setTimeout(()=>{
            if(oldStyleDownloadRef.current) {
                oldStyleDownloadRef.current.click();
            } else {
                if(ctr>10) {
                    console.error("Could not trigger download after 5 seconds, something has gone wrong.");
                } else {
                    triggerDownload(ctr+1);
                }
            }
        }, 500);

        triggerDownload(0);
    }

    const doDownload = async () => {
        try {
            const response = await axios.get<ObjectGetResponse<string>>(`/api/download/${props.archiveEntryId}`);
            // @ts-ignore
            if(window.showSaveFilePicker) {
                //the new style uses the File System Access API, which also requires a secure context to work.
                await newStyleDownload(response);
            } else {
                alert("Downloading like this works best in Chrome. If the download does not work, please load Chrome and try again in that.");
                await oldStyleDownload(response);
            }
        } catch(err)  {
            if(err.name && err.name==="AbortError") {
                //the user aborted the download, so don't alert them
                console.log("User cancelled download");
            } else {
                console.error("Could not download content: ", err);
                alert("Could not download, see browser console for details");
            }
        }
    }

    const oldStyleDownloadRef = createRef<HTMLAnchorElement>();

    return <div className={classes.centered}>
        <a ref={oldStyleDownloadRef} href={downloadUrl ?? "#"} style={{display:"none"}}/>
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
                            isDownloadable() ? <Tooltip title={downloading ? "Download in progress..." : "Download just this file"}>
                                {
                                    downloading ? <CircularProgress/> :
                                        <IconButton onClick={doDownload}>
                                            <GetApp/>
                                        </IconButton>
                                }
                            </Tooltip> : null
                        }
                    </Grid>
                </Grid>
            </Grid>
        </Grid>
        {
            props.lightboxEntry.availableUntil ? <>
                <Typography className={classes.runOnText}>
                    Available for{" "}
                </Typography>
                <TimestampFormatter relative={true} value={props.lightboxEntry.availableUntil} className={classes.runOnText}/>
            </> : <Typography className={classes.runOnText}>Available indefinitely</Typography>
        }
        {
            downloadedTo ? <Typography className={classes.smallText}><CheckCircle className={classes.successIcon}/>Downloaded to {downloadedTo}</Typography> : undefined
        }
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