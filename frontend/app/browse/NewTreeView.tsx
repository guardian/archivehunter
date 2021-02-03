import React, {useState, useEffect, ChangeEvent} from "react";
import {FormLabel, Grid, makeStyles, MenuItem, Select} from "@material-ui/core";
import {TreeView} from "@material-ui/lab";
import {ChevronRight, ExpandMore} from "@material-ui/icons";

interface NewTreeViewProps {
    currentCollection: string;
    collectionList: string[];
    collectionDidChange: (newCollection:string)=>void;
    pathSelectionChanged: (newPath:string)=>void;
}

const useStyles = makeStyles({
    root: {
        width: "100%"
    }
});

const NewTreeView:React.FC<NewTreeViewProps> = (props) => {
    const classes = useStyles();
    const [expanded, setExpanded] = useState<string[]>([]);

    const handleToggle = (evt: ChangeEvent<{}>, nodeIds: string[]) => {
        setExpanded(nodeIds);
    }

    const handleSelect = (event: React.ChangeEvent<{}>, nodeIds: string[]) => {
        if(nodeIds.length>0) {
            props.pathSelectionChanged(nodeIds[0])
        }
    }
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
                      defaultCollapseIcon={<ExpandMore/>}
                      defaultExpandIcon={<ChevronRight/>}
                      expanded={expanded}
                      onNodeToggle={handleToggle}
                      onNodeSelect={handleSelect}>

            </TreeView>
        </Grid>
    </Grid>
}

export default NewTreeView;