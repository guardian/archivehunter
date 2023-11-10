import React, {useState, useEffect} from "react";
import {RouteComponentProps} from "react-router";
import {CircularProgress, Grid, Input, makeStyles, Snackbar, Select, MenuItem} from "@material-ui/core";
import {Search} from "@material-ui/icons";
import MuiAlert from "@material-ui/lab/Alert";
import NewSearchComponent from "../common/NewSearchComponent";
import {ArchiveEntry} from "../types";
import EntryDetails from "../Entry/EntryDetails";
import {Helmet} from "react-helmet";

const useStyles = makeStyles({
    searchBox: {
        marginLeft: "auto",
        marginRight: "auto",
        width: "60vw",
        alignItems: "flex-start"
    },
    spinner: {
        width: "16px",
        height: "16px"
    },
    appArea: {
        display: "grid",
        gridTemplateColumns: "repeat(20, 5%)",
        marginTop: "2em",
        height: "90vh"
    }
});

const NewBasicSearch:React.FC<RouteComponentProps> = (props) => {
    const classes = useStyles();
    const [typedSearch, setTypedSearch] = useState("");     //this state var stores the text as it is typed
    const [activeSearch, setActiveSearch] = useState("");   //this state var is shared with searchComponent as the basicSearch param
    const [showingAlert, setShowingAlert] = useState(false);
    const [lastError, setLastError] = useState<string|undefined>(undefined);
    const [selectedEntry, setSelectedEntry] = useState<ArchiveEntry|undefined>(undefined);
    const [isLoading, setIsLoading] = useState(false);
    const [dividerLocation, setDividerLocation] = useState(20);
    const [newlyLightboxedList, setNewlyLightboxedList] = useState<string[]>([]);
    const [typeString, setTypeString] = useState<string>("Any");

    const closeAlert = ()=>setShowingAlert(false);

    /**
     * 1 second after the user finishes typing, update the search parameter to trigger the search
     */
    useEffect(()=>{
        const timerId = window.setTimeout(()=>setActiveSearch(typedSearch), 1000);

        return ()=>{
            console.log(`cancelling previous timer ${timerId}`);
            window.clearTimeout(timerId);
        }
    }, [typedSearch]);

    useEffect(()=>{
        setActiveSearch("");
        const timerId2 = window.setTimeout(()=>setActiveSearch(typedSearch), 10);
    }, [typeString]);

    /**
     * if the user has selected an entry, then open the details panel; if s/he has deselected then close it.
     */
    useEffect(()=>{
        if(selectedEntry) {
            setDividerLocation(16); //panel open
        } else {
            setDividerLocation(20); //panel closed
        }
    }, [selectedEntry]);

    return <>
        <Helmet>
            <title>Search - ArchiveHunter</title>
        </Helmet>
        <Snackbar open={showingAlert} onClose={closeAlert} autoHideDuration={8000}>
            <MuiAlert severity="error" onClose={closeAlert}>{lastError}</MuiAlert>
        </Snackbar>
        <Grid container className={classes.searchBox} spacing={2}>
            <Grid item><Search/></Grid>
            <Grid item style={{flexGrow: 1}}>
                <Input onChange={(evt)=>setTypedSearch(evt.target.value)}
                       value={typedSearch}
                       style={{width: "100%"}}/>
            </Grid>
            <Grid item style={{flexGrow: 1}}>
                Type: <Select id="filter-type" value={typeString} onChange={(evt) => setTypeString(evt.target.value as string)} >
                <MenuItem value="Any">Any</MenuItem>
                <MenuItem value="application/gzip">application/gzip</MenuItem>
                <MenuItem value="application/javascript">application/javascript</MenuItem>
                <MenuItem value="application/json">application/json</MenuItem>
                <MenuItem value="application/msword">application/msword</MenuItem>
                <MenuItem value="application/mxf">application/mxf</MenuItem>
                <MenuItem value="application/octet-stream">application/octet-stream</MenuItem>
                <MenuItem value="application/pdf">application/pdf</MenuItem>
                <MenuItem value="application/photoshop">application/photoshop</MenuItem>
                <MenuItem value="application/postscript">application/postscript</MenuItem>
                <MenuItem value="application/psd">application/psd</MenuItem>
                <MenuItem value="application/rtf">application/rtf</MenuItem>
                <MenuItem value="application/x-7z-compressed">application/x-7z-compressed</MenuItem>
                <MenuItem value="application/x-cdf">application/x-cdf</MenuItem>
                <MenuItem value="application/x-gzip">application/x-gzip</MenuItem>
                <MenuItem value="application/x-photoshop">application/x-photoshop</MenuItem>
                <MenuItem value="application/x-tar">application/x-tar</MenuItem>
                <MenuItem value="application/xml">application/xml</MenuItem>
                <MenuItem value="application/zip">application/zip</MenuItem>
                <MenuItem value="audio/aac">audio/aac</MenuItem>
                <MenuItem value="audio/aiff">audio/aiff</MenuItem>
                <MenuItem value="audio/midi">audio/midi</MenuItem>
                <MenuItem value="audio/mpeg">audio/mpeg</MenuItem>
                <MenuItem value="audio/ogg">audio/ogg</MenuItem>
                <MenuItem value="audio/wav">audio/wav</MenuItem>
                <MenuItem value="audio/x-aiff">audio/x-aiff</MenuItem>
                <MenuItem value="audio/x-wav">audio/x-wav</MenuItem>
                <MenuItem value="binary/octet-stream">binary/octet-stream</MenuItem>
                <MenuItem value="image/bmp">image/bmp</MenuItem>
                <MenuItem value="image/gif">image/gif</MenuItem>
                <MenuItem value="image/jpeg">image/jpeg</MenuItem>
                <MenuItem value="image/png">image/png</MenuItem>
                <MenuItem value="image/psd">image/psd</MenuItem>
                <MenuItem value="image/tiff">image/tiff</MenuItem>
                <MenuItem value="image/vnd.adobe.photoshop">image/vnd.adobe.photoshop</MenuItem>
                <MenuItem value="image/x-icon">image/x-icon</MenuItem>
                <MenuItem value="text/css">text/css</MenuItem>
                <MenuItem value="text/csv">text/csv</MenuItem>
                <MenuItem value="text/html">text/html</MenuItem>
                <MenuItem value="text/plain">text/plain</MenuItem>
                <MenuItem value="text/richtext">text/richtext</MenuItem>
                <MenuItem value="text/xml">text/xml</MenuItem>
                <MenuItem value="video/mp4">video/mp4</MenuItem>
                <MenuItem value="video/mpeg">video/mpeg</MenuItem>
                <MenuItem value="video/ogg">video/ogg</MenuItem>
                <MenuItem value="video/quicktime">video/quicktime</MenuItem>
                <MenuItem value="video/webm">video/webm</MenuItem>
                <MenuItem value="video/x-msvideo">video/x-msvideo</MenuItem>
                <MenuItem value="video/x-sgi-movie">video/x-sgi-movie</MenuItem>
                <MenuItem value="video/x-flv">video/x-flv</MenuItem>
            </Select>
            </Grid>
            {
                isLoading ? <Grid item className={classes.spinner}><CircularProgress/></Grid> : null
            }
        </Grid>
        <div className={classes.appArea}>

            <div style={{ gridColumnStart: 1, gridColumnEnd: dividerLocation}}>
                <NewSearchComponent pageSize={25}
                                    itemLimit={100}
                                    onEntryClicked={(entry)=>setSelectedEntry(entry)}
                                    onErrorOccurred={(error)=>{
                                        setLastError(error);
                                        setShowingAlert(true);
                                    }}
                                    basicQuery={activeSearch}
                                    selectedEntry={selectedEntry}
                                    onLoadingStarted={()=>setIsLoading(true)}
                                    onLoadingFinished={()=>setIsLoading(false)}
                                    newlyLightboxed={newlyLightboxedList}
                                    typeQuery={typeString}
                />
            </div>
            <div style={{gridColumnStart: dividerLocation, gridColumnEnd: -1}}>
                { selectedEntry ? <EntryDetails entry={selectedEntry}
                              autoPlay={true}
                              showJobs={true}
                              loadJobs={false}
                              onError={(message:string)=>{
                                  setLastError(message);
                                  setShowingAlert(true);
                              }}
                              openClicked={(itemId:string)=>props.history.push(`/item/${encodeURIComponent(itemId)}`)}
                              //when the user adds to lightbox we record it here. This state var is bound to the NewSearchComponent
                                //which will then re-load data for the given entry (after a short delay)
                              lightboxedCb={(entryId:string)=>setNewlyLightboxedList((prevState) => prevState.concat(entryId))}
                /> : undefined }
            </div>
        </div>
    </>
}

export default NewBasicSearch;