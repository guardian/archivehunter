import React from 'react';
import {makeStyles, Switch} from "@material-ui/core";

interface CollectionSelectorProps {
    collections: string[];  //the list of all collections to display
    userSelected: string[]; //the list of collections currently selected for the user
    selectionUpdated: (newValues:string[])=>void;  //callback indicating that a value has been added
    disabled?: boolean; //if true, don't allow editing
}

const useStyles = makeStyles({
    collectionsList: {
        listStyle: "none",
        paddingLeft: "0.2em",
        paddingRight: "0.2em",
        lineHeight: "40px"
    }
});

const CollectionSelector:React.FC<CollectionSelectorProps> = (props) => {
    const classes = useStyles();

    const selectionUpdated = (evt:any, collectionName:string) => {
        if(!evt.target) return
        console.log("selectionUpdated: ", collectionName, evt.target.checked);
        //if we are _currently_ checked, then remove the value, if we are currently unchecked then add it
        const updatedValue = evt.target.checked ? props.userSelected.filter(entry=>entry!==collectionName) :
            props.userSelected.concat(collectionName) ;

        if(props.selectionUpdated){
            props.selectionUpdated(updatedValue)
        }
    }

    return <ul className={classes.collectionsList}>
        {
            props.collections.map(collectionName=>
                <li key={collectionName} className={props.disabled ? "collection-list-item-disabled" : "collection-list-item"}>
                    <Switch disabled={props.disabled}
                            checked={props.userSelected ? props.userSelected.includes(collectionName) : false}
                            onClick={(evt)=>selectionUpdated(evt, collectionName)}/>{collectionName}</li>)
        }
    </ul>
}

export default CollectionSelector;