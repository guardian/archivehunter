import React, {useState, useEffect} from "react";
import {AdvancedSearchDoc, ArchiveEntry, SearchResponse} from "../types";
import axios, {CancelToken} from "axios";
import ErrorViewComponent from "./ErrorViewComponent";
import {Grid, makeStyles} from "@material-ui/core";
import EntryView from "../search/EntryView";

interface NewSearchComponentProps {
    advancedSearch?: AdvancedSearchDoc; //if advancedSearch is set, then it is preferred over basicQuery
    basicQuery?: string;
    pageSize: number;
    itemLimit: number;
    selectedEntry?: ArchiveEntry;
    onEntryClicked: (entry:ArchiveEntry)=>void;         //called when an entry is clicked
    onErrorOccurred: (errorDescription:string)=>void;   //called when a load error occurs, parent should display the error
}

const useStyles = makeStyles({
    searchResultsContainer: {
        overflowY: "scroll",
        overflowX: "hidden"
    }
});

const NewSearchComponent:React.FC<NewSearchComponentProps> = (props) => {
    const [entries, setEntries] = useState<ArchiveEntry[]>([]);
    const [cancelToken, setCancelToken] = useState<CancelToken|undefined>(undefined);
    const classes = useStyles();

    /**
     * recursively load in pages from the server.  This will recurse until either all results are loaded or
     * props.itemLimit is hit.
     * @param token cancelToken that will stop the operation.  If the cancelToken is activated, then the `entries` state
     * var will be blanked.
     * @param startAt item number to start at on the server, normally 0.
     */
    const loadNextPage:(token:CancelToken, startAt:number)=>Promise<boolean> = async (token:CancelToken, startAt: number)=>{
        try {
            if(startAt+props.pageSize>props.itemLimit) {
                console.log(`Hit the item limit of ${props.itemLimit}, stopping`);
                return true;
            }
            const results = props.advancedSearch ?
                await axios.post<SearchResponse>("/api/search/browser", {
                    body: JSON.stringify(props.advancedSearch),
                    cancelToken: token
                }) :
                await axios.get<SearchResponse>("/api/search/basic?q=" + encodeURIComponent(props.basicQuery as string) + "&start=" + startAt + "&length=" + props.pageSize, {cancelToken: token})

            if(results.data.entries.length>0) {
                console.log(`Received ${results.data.entries.length} results, loading next page...`)
                setEntries((prev)=>prev.concat(results.data.entries));
                return loadNextPage(token, startAt+results.data.entries.length);
            } else {
                console.log("Received 0 results, assuming got to the end of iteration")
                return true;
            }
        } catch(err) {
            if(axios.isCancel(err)) {
                console.log("search was cancelled, clearing results");
                setEntries([]);
                return false;
            } else {
                props.onErrorOccurred(ErrorViewComponent.formatError(err, false));
                return false;
            }
        }
    }

    /**
     * when the search parameters change, then load in new data
     */
    useEffect(()=>{
        const cancelTokenFactory = axios.CancelToken.source();
        setCancelToken(cancelTokenFactory.token);

        loadNextPage(cancelTokenFactory.token, 0);
        return ()=>{
            cancelTokenFactory.cancel("search interrupted");
        }
    }, [props.advancedSearch, props.basicQuery]);

    return <Grid container className={classes.searchResultsContainer}>
        {
            entries.map((entry,idx)=><EntryView
                                                key={idx}
                                                isSelected={ props.selectedEntry ? props.selectedEntry.id===entry.id : false}
                                                entry={entry}
                                                cancelToken={cancelToken}
                                                itemOpenRequest={props.onEntryClicked}
            />)
        }
    </Grid>
}

export default NewSearchComponent;