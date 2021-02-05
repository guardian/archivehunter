import React, {useState} from 'react';
import TimestampFormatter from "../common/TimestampFormatter";
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";
import axios from 'axios';
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import {LightboxBulk} from "../types";
import {formatError} from "../common/ErrorViewComponent";
import {makeStyles, Tooltip, Typography} from "@material-ui/core";
import clsx from "clsx";

interface BulkSelectionsScrollProps {
    entries: LightboxBulk[];
    currentSelection?: string;
    onSelected: (newId:string)=>void;
    onDeleteClicked: (idToDelete:string)=>void;
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
        height: "95px",
        width: "280px",
        overflow: "hidden",
        whiteSpace: "nowrap"
    },
    bulkSelectionScroll: {
        overflowY: "hidden",
        overflowX: "auto",
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
    }
}));

const BulkSelectionsScroll:React.FC<BulkSelectionsScrollProps> = (props) => {
    const [showRestoreSpinner, setShowRestoreSpinner] = useState(false);
    const classes = useStyles();

    const nameExtractor = /^([^:]+):(.*)$/;
    //
    // entryClicked(newId) {
    //     if(this.props.onSelected){
    //         this.props.onSelected(newId);
    //     }
    // }

    const extractNameAndPathArray = (str:string) => {
        const result = nameExtractor.exec(str);
        if(result){
            return ({name: result[1], pathArray: result[2].split("/")})
        } else {
            return ({name: str, pathArray: []})
        }
    }

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
            if ((date - addedTime) > setting) {
                return "inline"
            } else {
                return "none"
            }
        } catch (err) {
            console.error("could not set expiry warning: ", err);
            if(props.onError) props.onError("An internal error occurred calculating expiry time")
        }
    }

    return <div className={classes.bulkSelectionScroll}>
        {
            props.entries.map((entry,idx)=>{
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
                        <FontAwesomeIcon style={{marginRight: "0.5em"}} icon="list-ol"/>{entry.availCount} items
                    </Typography>
                    <div style={{overflow:"hidden", width:"100%"}}>
                        <a onClick={(evt)=>{
                            evt.preventDefault();
                            initiateDownloadInApp(entry.id);
                            return false;
                        }} className={classes.bulkDownloadLink}>Download in app</a>

                        {props.isAdmin ?
                            <a onClick={() => {
                                initiateRedoBulk(entry.id);
                                return false
                            }} className={classes.redoRestoreLink}>
                                <LoadingThrobber show={showRestoreSpinner} small={true} inline={true}/>
                                Redo restore of folder
                            </a> : ""
                        }

                    </div>
                    <FontAwesomeIcon icon="trash-alt" className={classes.clickable} style={{color: "red", float: "right"}} onClick={evt=>{
                        evt.stopPropagation();
                        props.onDeleteClicked(entry.id);
                    }}/>
                    <Tooltip title="Some or all of the media may have returned to the deep archive and therefore be unavailable.">
                        <span><FontAwesomeIcon icon="exclamation-triangle"
                                         style={{marginRight: "6px" ,color: "red", float: "left", display: showExpiryWarning(entry.addedAt)}}
                        /></span>
                    </Tooltip>
                    <Typography className="entry-date black">Added <TimestampFormatter relative={true} value={entry.addedAt}/></Typography>
                </div>
            })
        }
    </div>

}

export default BulkSelectionsScroll;