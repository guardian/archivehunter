import React from 'react';
import TimestampFormatter from "../common/TimestampFormatter";
import EntryThumbnail from "../Entry/EntryThumbnail.jsx";
import EntryLightboxBanner from '../Entry/EntryLightboxBanner.jsx';
import {Grid, makeStyles, Typography} from "@material-ui/core";
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

const useStyles = makeStyles({
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
        overflow: "hidden"
    },
    entryIcon: {
        color: "#333333"
    },
    entryStandard: {
        backgroundColor: "lightgray !important",
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
    }
});

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
        <Typography className={classes.entryTitle}><Folder className={classes.entryIcon}/>{filename()}</Typography>
        <EntryThumbnail mimeType={props.entry.mimeType} entryId={props.entry.id} cancelToken={props.cancelToken} fileExtension={props.entry.file_extension}/>
        <EntryLightboxBanner lightboxEntries={props.entry.lightboxEntries} entryClassName="entry-lightbox-banner-entry-small"/>
        <p className="entry-date"><TimestampFormatter relative={false}
                                                      value={props.entry.last_modified}
                                                      formatString="Do MMM YYYY, h:mm a"/></p>
    </Grid>
}

export default EntryView;