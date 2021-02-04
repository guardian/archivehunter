import React, {useState, useEffect} from "react";
import {FormLabel, Grid, makeStyles, MenuItem, Select, Typography} from "@material-ui/core";
import {TreeItem, TreeItemClassKey, TreeView} from "@material-ui/lab";
import {ChevronRight, ExpandMore} from "@material-ui/icons";
import {BrowseDirectoryResponse, PathEntry} from "../types";
import axios from "axios";
import {formatError} from "../common/ErrorViewComponent";

interface NewTreeViewProps {
    currentCollection: string;
    collectionList: string[];
    collectionDidChange: (newCollection:string)=>void;
    pathSelectionChanged: (newPath:string)=>void;
    onError: (errorDesc:string)=>void;
}

const useStyles = makeStyles({
    root: {
        width: "100%"
    }
});

// const findParentPath = (forPath:string, loadedPaths:PathEntry[]) => {
//     const pathParts = forPath
//         .split("/")
//         .filter(part=>part.length>0);
//
//     console.log("pathParts are ", pathParts);
//
//     /**
//      * recursively traverses the tree looking for the right name at each place
//      * @param pathSegment
//      * @param within
//      * @param remainingSegments
//      */
//     const recursiveFind:(pathSegment:string,within:PathEntry[],remainingSegments:string[])=>PathEntry|undefined = (pathSegment, within, remainingSegments) => {
//         const matches = within.filter(entry=>entry.name==pathSegment);
//         if(matches.length>0) {
//             if(remainingSegments.length>0) {
//                 return recursiveFind(remainingSegments[0], matches[0].children, remainingSegments.slice(1))
//             } else {
//                 //we are done
//                 return matches[0];
//             }
//         } else {
//             console.error("No match found for ", pathSegment, " within ", within);
//             return undefined;
//         }
//     }
//
//     return recursiveFind(pathParts[0], loadedPaths, pathParts.slice(1))
// }

interface TreeLeafProps {
    path:PathEntry;
    leafWasSelected:(entry:PathEntry)=>void;
    collectionName: string;
    parentKey:string;
    onError?: (errorDesc:string)=>void;
}

/**
 * loads in another level of paths below the path given
 * @param collectionName bucket to load paths from
 * @param from directory to start at. Use an empty string to mean "root".
 */
const loadInPaths = async (collectionName:string, from:string)=> {
    const nameExtractor = /\/([^\/]+)\/$/;

    const result = await axios.get<BrowseDirectoryResponse>(`/api/browse/${collectionName}?prefix=${from}`);
    const pathsToAdd: PathEntry[] = result.data.entries.map((fullpath, idx) => {
        const extracted = nameExtractor.exec("/"+fullpath);
        console.log("extraction result was ", extracted);

        const name = extracted ? extracted[1] : "";
        return {
            name: name,
            //children: [],
            // hasLoaded: false,
            fullpath: fullpath,
            idx: idx
            //parent: parent
        }
    });

    return pathsToAdd
}

/**
 * TreeLeaf is a recursive component that handles a single level of directories.
 */
const TreeLeaf:React.FC<TreeLeafProps> = (props) => {
    const [childNodes, setChildNodes] = useState<PathEntry[]>([]);
    const [isLoaded, setIsLoaded] = useState(false);

    const handleToggle = (evt:React.MouseEvent<Element, MouseEvent>) => {
        //if(evt) evt.preventDefault();
        //console.log("toggle clicked")

        if(!isLoaded) {
            //save the event so we can re-play it once the async call is done
            if(evt) evt.persist();

            //console.log("loading in more content")
            loadInPaths(props.collectionName, props.path.fullpath)
                .then(morePaths => {
                    //console.log("setting ", morePaths);
                    setChildNodes(morePaths);
                    setIsLoaded(true);
                    /* the component view has already refreshed by the time our load has finished.
                    * so, we must re-trigger the event here to get it to actually display the new data otherwise
                    * it won't get displayed until the user clicks to expand for a second time
                     */
                    if(evt) evt.target.dispatchEvent(evt.nativeEvent);
                })
                .catch(err => {
                    console.error(`Could not load more paths for ${props.path.fullpath} on ${props.collectionName}`, err);
                    if (props.onError) props.onError(formatError(err, false))
                })
        }
    }

    const handleSelect = (evt:React.MouseEvent<Element, MouseEvent>)=> {
        evt.preventDefault();
        props.leafWasSelected(props.path);
    }

    //console.log("re-render ", props.path, childNodes);

    return <TreeItem nodeId={props.path.fullpath}
                     label={<Typography>{props.path.name}</Typography>}
                     collapseIcon={<ExpandMore/>}
                     key={props.parentKey}
                     //icon={isOpen ? <ExpandMore/> : <ChevronRight/>}
                     expandIcon={<ChevronRight/>}
                     endIcon={<ChevronRight/>}
                     onIconClick={handleToggle}
                     onLabelClick={handleSelect}
    >{
        childNodes.map((childPath, idx)=> {
            //console.log(childPath.fullpath)
            return <TreeLeaf path={childPath}
                      key={`${props.path.idx}${idx}`}
                      parentKey={`${props.path.idx}-${idx}`}
                      leafWasSelected={props.leafWasSelected}
                      onError={props.onError}
                      collectionName={props.collectionName}
            />
        })
    }</TreeItem>
}

const NewTreeView:React.FC<NewTreeViewProps> = (props) => {
    const classes = useStyles();
    const [loadedPaths, setLoadedPaths] = useState<PathEntry[]>([]);

    const treeItemSelected = (path:PathEntry) => {
        console.log("You selected ", path);
        props.pathSelectionChanged(path.fullpath);
    }

    useEffect(()=>{
        if(props.currentCollection=="") {
            //console.log("note, can't load paths for non-existent collection");
            setLoadedPaths([]);
        } else {
            loadInPaths(props.currentCollection, "")
                .then(paths=>setLoadedPaths(paths))
                .catch(err=>{
                    //console.error("Could not perform initial load of paths: ", err);
                    props.onError(formatError(err, false))
                })
        }
    }, [props.currentCollection]);

    return <Grid container direction="column" spacing={3}>
        <Grid item>
            <FormLabel htmlFor="collection-selector">Collection</FormLabel>
            <Select id="collection-selector"
                    onChange={(evt)=>props.collectionDidChange(evt.target.value as string)}
                    className={classes.root}
                    value={props.currentCollection}>
                {
                    props.collectionList.map((entry, idx)=><MenuItem key={idx} value={entry}>{entry}</MenuItem>)
                }
            </Select>
        </Grid>
        <Grid item>
            <TreeView className={classes.root}
                      defaultCollapseIcon={<ChevronRight/>}
                      defaultExpandIcon={<ExpandMore/>}
                      defaultExpanded={[]}
                      onNodeToggle={()=>console.log("onnodeToggle")}>
                {
                    loadedPaths.length==0 ? <Typography variant="caption">No subfolders present</Typography>
                        : loadedPaths.map((path, idx)=><TreeLeaf path={path}
                                                           key={idx.toString()}
                                                           parentKey={idx.toString()}
                                                           leafWasSelected={treeItemSelected}
                                                           collectionName={props.currentCollection}/>)
                }
            </TreeView>
        </Grid>
    </Grid>
}

export {loadInPaths, TreeLeaf};

export default NewTreeView;