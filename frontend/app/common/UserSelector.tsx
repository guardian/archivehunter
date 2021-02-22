import React, {useEffect, useState} from 'react';
import axios from 'axios';
import {formatError} from "./ErrorViewComponent";
import {FormLabel, makeStyles, MenuItem, Select} from "@material-ui/core";

interface UserSelectorProps {
    onChange: (newUser:string)=>void;
    selectedUser: string;
    onError?: (errorDesc:string)=>void;
}

const useStyles = makeStyles({
    root: {
        width: "100%"
    }
});

const UserSelector:React.FC<UserSelectorProps> = (props) => {
    const [loading, setLoading] = useState(false);
    const [userList, setUserList] = useState<string[]>([]);
    const classes = useStyles();

    useEffect(()=>{
        setLoading(true);
        axios.get("/api/user")
            .then(response=>{
                setUserList(response.data.entries.map( (entry:any)=>entry.userEmail));
                setLoading(false);
            })
            .catch(err=>{
                console.error("could not load in users list: ", err);
                if(props.onError) props.onError(formatError(err, false));
            })
    }, []);

    return <>
        <FormLabel id="user-selector-label" htmlFor="user-selector">Other users' lightboxes</FormLabel>
        <Select className={classes.root}
                id="user-selector"
                   onChange={(evt)=>props.onChange(evt.target.value as string)}
                   value={props.selectedUser}>
            <MenuItem value="my">(mine)</MenuItem>
        {
            userList.map(entry=><MenuItem key={entry} value={entry}>{entry}</MenuItem>)
        }
    </Select>
        </>
}

export default UserSelector;