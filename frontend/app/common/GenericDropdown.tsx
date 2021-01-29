import React, {ChangeEvent} from 'react';
import {MenuItem, Select} from "@material-ui/core";

interface GenericDropdownProps {
    valueList: string[];
    onChange: (event:ChangeEvent<{name?:string, value:any}>)=>void;
    value: string;
}

const GenericDropdown:React.FC<GenericDropdownProps> = (props) => {
        return <Select onChange={props.onChange} value={props.value}>
            {
                props.valueList.map(entry=><MenuItem key={entry} value={entry}>{entry}</MenuItem>)
            }
        </Select>
}

export default GenericDropdown;