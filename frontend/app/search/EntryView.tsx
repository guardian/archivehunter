import React from 'react';
import TimestampFormatter from "../common/TimestampFormatter";
import EntryThumbnail from "../Entry/EntryThumbnail";
import EntryLightboxBanner from '../Entry/EntryLightboxBanner';
import {Grid, makeStyles, Tooltip, Typography} from "@material-ui/core";
import {ArchiveEntry} from "../types";
import {CancelToken} from "axios";
import clsx from "clsx";
import {Folder} from "@material-ui/icons";

interface EntryViewProps {
    entry: ArchiveEntry;
    itemOpenRequest: (entry:ArchiveEntry)=>void;
    isSelected: boolean;
    cancelToken: CancelToken|undefined;
}

const useStyles = makeStyles((theme)=>({
    entryView: {
        border: "1px solid black",
        overflow: "hidden",
        width: "140px",
        height: "150px",
        borderRadius: "10px",
        backgroundColor: "white",
        marginRight: "2em",
        marginBottom: "2em",
        padding: "0.4em",
        paddingTop: "0.2em",
        cursor: "pointer"
    },
    entryTitle: {
        marginTop: 0,
        marginBottom: 0,
        color: "blue",
        fontSize: "0.8em",
        fontWeight: "bold",
        backgroundColor: "inherit",
        marginLeft: "0.2em",
        marginRight: "0.2em",
        height: "2.5em",
        overflow: "hidden",
        wordBreak: "break-all"
    },
    entryIcon: {
        color: "#333333",
        verticalAlign: "top"
    },
    entryStandard: {
        backgroundColor: "lightgray !important",
        color: "#333333"
    },
    entryShallowArchive: {
        backgroundColor: "#cceeff !important",
    },
    entryDeepArchive: {
        backgroundColor: "#2676b5 !important",
        color: "white !important"
    },
    entryViewSelected: {
        borderColor: "white !important",
        boxShadow: "3px 3px 5px black"
    },
    entryGoneMissing: {
        backgroundColor: "darkred !important",
        color: "white !important"
    },
    entryDate: {
        fontSize: "0.7em",
        fontStyle: "italic",
        backgroundColor: "inherit",
        marginTop:0
    },
    centered: {
        marginLeft: "auto",
        marginRight: "auto"
    }
}));

const EntryView:React.FC<EntryViewProps> = (props) => {
    const classes = useStyles();

    const entryClicked = ()=>{
        if(props.itemOpenRequest) props.itemOpenRequest(props.entry);
    }

    const filename= ()=>{
        const fnParts = props.entry.path.split("/");
        return fnParts.slice(-1);
    }

    const classList = {
        [classes.entryView]: true,
        [classes.entryStandard]: props.entry.storageClass=="STANDARD",
        [classes.entryShallowArchive]: props.entry.storageClass=="STANDARD_IA",
        [classes.entryDeepArchive]: props.entry.storageClass=="GLACIER",
        [classes.entryViewSelected]: props.isSelected,
        [classes.entryGoneMissing]: props.entry.beenDeleted
    };

    return <Grid item className={clsx(classList)} onClick={entryClicked}>
        <Tooltip title={filename()}>
            <Typography className={classes.entryTitle}>
                <Folder className={classes.entryIcon}/>{filename()}
            </Typography>
        </Tooltip>

        <div className={classes.centered}>
            <EntryThumbnail mimeType={props.entry.mimeType} entryId={props.entry.id} cancelToken={props.cancelToken} fileExtension={props.entry.file_extension}/>
        </div>
        <EntryLightboxBanner lightboxEntries={props.entry.lightboxEntries} small={true}/>
        <TimestampFormatter relative={false}
                            className={classes.entryDate}
                            value={props.entry.last_modified}
                            formatString="Do MMM YYYY, h:mm a"/>
    </Grid>
}

export default EntryView;